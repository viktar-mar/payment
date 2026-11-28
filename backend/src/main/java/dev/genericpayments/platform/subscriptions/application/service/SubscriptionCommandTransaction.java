package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Reply;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import java.util.UUID;
import org.springframework.stereotype.Component;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.sites.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Clock;
import java.util.Objects;
@Component
public class SubscriptionCommandTransaction {
    private final SubscriptionTransactionPort tx;
    private final SubscriptionOperationStore operations;
    private final SubscriptionStore subscriptions;
    private final TrialEligibilityStore trials;
    private final SubscriptionPolicyPort policies;
    private final SubscriptionAuthorityPort authority;
    private final SubscriptionCatalogPort catalog;
    private final LoadSitePort sites;
    private final LockSitePort siteLocks;
    private final CustomerStore customers;
    private final LockPaymentAccountPort accounts;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private SubscriptionBillingProofStore billingProofs;
    @org.springframework.beans.factory.annotation.Autowired private SubscriptionObjectPort providerObjects;
    public SubscriptionCommandTransaction(SubscriptionTransactionPort tx,SubscriptionOperationStore operations,SubscriptionStore subscriptions,
        TrialEligibilityStore trials,SubscriptionPolicyPort policies,SubscriptionAuthorityPort authority,SubscriptionCatalogPort catalog,
        LoadSitePort sites,LockSitePort siteLocks,CustomerStore customers,LockPaymentAccountPort accounts,Clock clock) {
        this.tx=tx;this.operations=operations;this.subscriptions=subscriptions;this.trials=trials;this.policies=policies;
        this.authority=authority;this.catalog=catalog;this.sites=sites;this.siteLocks=siteLocks;this.customers=customers;this.accounts=accounts;this.clock=clock;
    }
    public record ReservedCreate(SubscriptionOperation operation,Subscription subscription,PaymentAccount account,boolean owner) {}
    public void requireOutsideTransaction(){if(tx.active())throw new IllegalStateException("Provider workflow requires no ambient transaction");}

    public ReservedCreate reserveCreate(CreateSubscriptionUseCase.Command command) {
        requireOutsideTransaction();
        if(command==null||command.context()==null)throw denied();
        var context=command.context();String principal=principal(context);
        if(context.idempotencyKey()==null||context.idempotencyKey().isBlank()||context.idempotencyKey().length()>255||context.idempotencyKey().codePoints().anyMatch(Character::isISOControl))throw conflict();
        // Nonlocking candidate inputs only. Every decision is checked again after claim and authority locks.
        var site=sites.findById(context.siteId()).orElseThrow(SubscriptionCommandTransaction::denied);
        customers.findById(context.siteId(),context.customerId()).orElseThrow(SubscriptionCommandTransaction::denied);
        var terms=catalog.terms(context.siteId(),command.priceId());
        var policy=policies.load(context.siteId());
        boolean trial=trialChoice(context,terms,false);
        // PostgreSQL retains microseconds; keep operation times stable across claim/save round trips.
        var now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var id=UUID.randomUUID();
        var candidate=new SubscriptionOperation(id,context.siteId(),context.customerId(),null,SubscriptionOperation.Kind.CREATE,
            SubscriptionOperation.Status.RESERVED,principal,CreateSubscriptionFingerprint.digest(context.idempotencyKey()),
            CreateSubscriptionFingerprint.encode(command,principal,terms,0,policy.version(),trial),
            "subscription:"+site.paymentAccountId().value()+":"+id+":CREATE",0,policy.version(),null,null,now,now,0);
        return tx.requiresNew(()->{
            var claim=operations.claim(candidate);var operation=claim.operation();
            authority.revalidate(context,true);
            if(!claim.owner()) {
                boolean originalTrial=CreateSubscriptionFingerprint.startsTrial(operation.fingerprint());
                if(!operation.customerId().equals(context.customerId())||!operation.fingerprint().equals(CreateSubscriptionFingerprint.encode(command,principal,terms,operation.expectedRevision(),operation.policyVersion(),originalTrial)))throw conflict();
                if(operation.subscriptionId()==null)throw conflict();
                var stored=subscriptions.find(context.siteId(),operation.subscriptionId()).orElseThrow(SubscriptionCommandTransaction::conflict);
                var pinned=accounts.findByIdForUpdate(stored.paymentAccountId()).orElseThrow(SubscriptionCommandTransaction::conflict);
                return new ReservedCreate(operation,subscriptions.lock(context.siteId(),stored.id()),pinned,false);
            }
            var currentSite=sites.findById(context.siteId()).orElseThrow(SubscriptionCommandTransaction::denied);
            var currentPolicy=policies.load(context.siteId());
            if(!command.recurringConsent()||!currentPolicy.enabled()||currentPolicy.version()!=operation.policyVersion()
                ||!currentSite.paymentAccountId().equals(site.paymentAccountId())||!catalog.published(context.siteId(),command.priceId()).equals(terms)
                ||trialChoice(context,terms,true)!=trial)throw conflict();
            var account=accounts.findByIdForUpdate(currentSite.paymentAccountId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            if(account.status()!=PaymentAccountStatus.ACTIVE||subscriptions.hasNonTerminalProduct(context.siteId(),context.customerId(),terms.productId()))throw conflict();
            var subscription=new Subscription(UUID.randomUUID(),context.siteId(),context.customerId(),terms.productId(),account.id(),terms,null,
                Subscription.Status.INCOMPLETE,null,null,null,null,null,null,null,null,0,now,now,null);
            subscriptions.save(subscription,-1);
            if(trial&&!trials.reserve(context.siteId(),context.customerId(),terms.productId(),operation.id()))throw conflict();
            operation=saveOperation(operation,subscription.id(),operation.status(),null,null);
            return new ReservedCreate(operation,subscription,account,true);
        });
    }
    private boolean trialChoice(SubscriptionCommandContext context,SubscriptionPriceTerms terms,boolean locked) {
        if(terms.trialDays()==0)return false;
        var eligibility=trials.find(context.siteId(),context.customerId(),terms.productId());
        if(eligibility.isPresent()) {
            if(eligibility.get().consumedAt()!=null)return false;
            if(locked&&eligibility.get().reservedByOperation()!=null)throw conflict();
        }
        return true;
    }

    /** Receipt observation locks the same lifecycle rows but does not require still-active request authority.
     * The committed provider result must remain recordable after logout, disablement or account rotation. */
    public SubscriptionOperationResult recordReply(UUID operationId,Reply reply) {
        Objects.requireNonNull(reply);
        return tx.requiresNew(()->{
            var operation=operations.lock(operationId);
            if(operation.kind()!=SubscriptionOperation.Kind.CREATE||operation.subscriptionId()==null)throw conflict();
            siteLocks.findByIdForUpdate(operation.siteId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            customers.findByIdForUpdate(operation.siteId(),operation.customerId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            var stored=subscriptions.find(operation.siteId(),operation.subscriptionId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            accounts.findByIdForUpdate(stored.paymentAccountId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            var subscription=subscriptions.lock(operation.siteId(),stored.id());
            boolean rejectedWithoutResource=reply.outcome()==SubscriptionProviderModels.Outcome.DECLINED&&reply.reference()==null;
            boolean started=subscription.billingStatus()!=Subscription.Status.INCOMPLETE||subscription.trial()!=null
                ||subscription.paidThrough()!=null||subscription.entitledPrice()!=null||subscription.currentPeriod()!=null
                ||subscription.pendingChange()!=null||subscription.cancellation()!=null||subscription.debt()!=null;
            boolean closedNeverCreated=operation.status()==SubscriptionOperation.Status.FAILED
                &&operation.providerReference()==null&&subscription.providerReference()==null
                &&subscription.billingStatus()==Subscription.Status.CANCELLED&&"CREATE_REJECTED".equals(subscription.terminalReason());
            if(closedNeverCreated) {
                if(reply.reference()!=null)throw conflict();
                return result(operation,null);
            }
            if(rejectedWithoutResource&&(operation.providerReference()!=null||subscription.providerReference()!=null||started))throw conflict();
            if(reply.reference()!=null&&((operation.providerReference()!=null&&!operation.providerReference().equals(reply.reference()))
                ||(subscription.providerReference()!=null&&!subscription.providerReference().equals(reply.reference()))))throw conflict();
            // A delayed receipt cannot undo authoritative resolution or reissue its capability.
            if(operation.status()==SubscriptionOperation.Status.SUCCEEDED||operation.status()==SubscriptionOperation.Status.FAILED)return result(operation,null);
            if(reply.reference()!=null&&subscription.providerReference()==null) {
                var s=subscription;
                subscription=subscriptions.save(new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),reply.reference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision()+1,s.createdAt(),clock.instant(),s.lastReconciledAt()),s.revision());
            }
            if(reply.outcome()==SubscriptionProviderModels.Outcome.DECLINED) {
                // Local absence of facts is not proof of pre-start failure on an existing provider resource.
                if(rejectedWithoutResource) {
                    trials.releaseConfirmedFailure(operation.id());
                    subscriptions.save(subscription.cancel("CREATE_REJECTED",clock.instant()),subscription.revision());
                }
            }
            var status=switch(reply.outcome()) {
                case ACCEPTED -> SubscriptionOperation.Status.PROCESSING;
                case REQUIRES_ACTION -> SubscriptionOperation.Status.REQUIRES_ACTION;
                case UNKNOWN -> SubscriptionOperation.Status.UNKNOWN;
                case DECLINED -> SubscriptionOperation.Status.FAILED;
            };
            operation=saveOperation(operation,operation.subscriptionId(),status,reply.reference()==null?operation.providerReference():reply.reference(),reply.outcome()==SubscriptionProviderModels.Outcome.DECLINED?"CREATE_REJECTED":null);
            return result(operation,reply.action());
        });
    }
    public SubscriptionOperationResult recordSetupReceipt(UUID operationId,SubscriptionProviderModels.PendingSetup pending){
        return tx.requiresNew(()->{
            var o=operations.lock(operationId);
            if(o.kind()!=SubscriptionOperation.Kind.CREATE||!operationId.equals(pending.receipt().operationId())||!CreateSubscriptionFingerprint.startsTrial(o.fingerprint()))throw conflict();
            siteLocks.findByIdForUpdate(o.siteId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            customers.findByIdForUpdate(o.siteId(),o.customerId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            var candidate=subscriptions.find(o.siteId(),o.subscriptionId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            accounts.findByIdForUpdate(candidate.paymentAccountId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            var s=subscriptions.lock(o.siteId(),o.subscriptionId());
            var proof=billingProofs.setup(s.paymentAccountId(),o.id()).orElseThrow(SubscriptionCommandTransaction::conflict);
            if(!proof.subscription().equals(s.id())||!proof.step().equals(pending.receipt().stepId())||!proof.reference().equals(pending.receipt().setupReference()))throw conflict();
            if(s.providerReference()!=null||o.providerReference()!=null||o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)throw conflict();
            providerObjects.bind(dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE,s,SubscriptionObjectPort.Kind.SETUP,proof.reference(),null,clock.instant());
            var saved=saveOperation(o,s.id(),pending.action()==null?SubscriptionOperation.Status.PROCESSING:SubscriptionOperation.Status.REQUIRES_ACTION,null,null);
            return result(saved,pending.action());
        });
    }
    public SubscriptionOperationResult recordSetupUpdateReply(UUID operationId,Reply reply){
        return tx.requiresNew(()->{
            var o=operations.lock(operationId);if(o.kind()!=SubscriptionOperation.Kind.SETUP)throw conflict();
            siteLocks.findByIdForUpdate(o.siteId()).orElseThrow(SubscriptionCommandTransaction::conflict);customers.findByIdForUpdate(o.siteId(),o.customerId()).orElseThrow(SubscriptionCommandTransaction::conflict);
            var candidate=subscriptions.find(o.siteId(),o.subscriptionId()).orElseThrow(SubscriptionCommandTransaction::conflict);accounts.findByIdForUpdate(candidate.paymentAccountId()).orElseThrow(SubscriptionCommandTransaction::conflict);var s=subscriptions.lock(o.siteId(),o.subscriptionId());
            if(reply.reference()!=null&&!reply.reference().equals(s.providerReference()))throw conflict();if(o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)return result(o,null);
            return result(saveOperation(o,s.id(),reply.outcome()==SubscriptionProviderModels.Outcome.UNKNOWN?SubscriptionOperation.Status.UNKNOWN:SubscriptionOperation.Status.PROCESSING,s.providerReference(),null),null);
        });
    }
    /** Internal continuation after authorized reservation; never a public unauthenticated lookup endpoint. */
    public SubscriptionOperationResult readResult(UUID operation){return tx.required(()->result(operations.lock(operation),null));}
    private SubscriptionOperation saveOperation(SubscriptionOperation o,UUID subscription,SubscriptionOperation.Status status,String reference,String failure) {
        return operations.save(new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),subscription,o.kind(),status,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),reference,failure,o.createdAt(),clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),o.version()+1),o.version());
    }
    private static SubscriptionOperationResult result(SubscriptionOperation o,SubscriptionProviderModels.NextAction action){return new SubscriptionOperationResult(o.id(),o.subscriptionId(),o.status(),action);}
    private static String principal(SubscriptionCommandContext c) {
        if(c.actor()==null||c.siteId()==null||c.customerId()==null)throw denied();
        return switch(c.actor()) {
            case SubscriptionActor.Customer actor -> {
                if(actor.session()==null||!actor.session().siteId().equals(c.siteId())||!actor.session().customerId().equals(c.customerId()))throw denied();
                yield "customer:"+actor.session().customerId().value();
            }
            case SubscriptionActor.Partner actor -> {
                if(actor.site()==null||!actor.site().siteId().equals(c.siteId()))throw denied();
                yield "partner:"+actor.site().keyId().value();
            }
            case SubscriptionActor.Admin actor -> {
                if(actor.admin()==null||actor.admin().id()==null)throw denied();
                yield "admin:"+actor.admin().id().value();
            }
        };
    }
    private static PaymentApplicationException denied(){return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");}
    private static PaymentApplicationException conflict(){return SubscriptionRuleErrors.conflict();}
}
