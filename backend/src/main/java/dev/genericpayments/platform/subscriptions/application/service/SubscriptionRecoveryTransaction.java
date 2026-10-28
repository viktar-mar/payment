package dev.genericpayments.platform.subscriptions.application.service;

import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;

@Component
public class SubscriptionRecoveryTransaction {
    private final SubscriptionTransactionPort tx;private final SubscriptionOperationStore operations;
    private final SubscriptionStore subscriptions;private final BillingRecordStore bills;
    private final SubscriptionAuthorityPort authority;private final SubscriptionPolicyPort policies;
    private final LockPaymentAccountPort accounts;private final LockSitePort sites;private final CustomerStore customers;
    private final SubscriptionProviderRegistry providers;private final Clock clock;
    public SubscriptionRecoveryTransaction(SubscriptionTransactionPort tx,SubscriptionOperationStore operations,SubscriptionStore subscriptions,
        BillingRecordStore bills,SubscriptionAuthorityPort authority,SubscriptionPolicyPort policies,LockPaymentAccountPort accounts,
        LockSitePort sites,CustomerStore customers,SubscriptionProviderRegistry providers,Clock clock){
        this.tx=tx;this.operations=operations;this.subscriptions=subscriptions;this.bills=bills;this.authority=authority;
        this.policies=policies;this.accounts=accounts;this.sites=sites;this.customers=customers;this.providers=providers;this.clock=clock;
    }
    public record Reserved(SubscriptionOperation operation,Subscription subscription,PaymentAccount account,String invoice,boolean owner) {}
    private record Candidate(Subscription subscription,PaymentAccount account,long policy) {}
    public void requireOutsideTransaction(){if(tx.active())throw new IllegalStateException("Provider workflow requires no ambient transaction");}
    public SubscriptionOperation reserveRecover(RecoverSubscriptionUseCase.Command command){return reserve(command).operation();}
    public SubscriptionOperation reserveResume(ResumeSubscriptionUseCase.Command command){return reserve(command).operation();}
    public Reserved reserve(RecoverSubscriptionUseCase.Command command){
        Objects.requireNonNull(command);return reserve(command.context(),command.subscriptionId(),command.billingRecordId(),false,SubscriptionOperation.Kind.RECOVER);
    }
    public Reserved reserve(ResumeSubscriptionUseCase.Command command){
        Objects.requireNonNull(command);return reserve(command.context(),command.subscriptionId(),null,command.recurringConsent(),SubscriptionOperation.Kind.RESUME);
    }
    private Reserved reserve(SubscriptionCommandContext context,UUID id,UUID billId,boolean consent,SubscriptionOperation.Kind kind){
        requireOutsideTransaction();String principal=principal(context);
        if(id==null||kind==SubscriptionOperation.Kind.RECOVER&&billId==null||context.idempotencyKey()==null||context.idempotencyKey().isBlank()
            ||context.idempotencyKey().length()>255||context.idempotencyKey().codePoints().anyMatch(Character::isISOControl))throw SubscriptionRuleErrors.conflict();
        var candidate=tx.required(()->{
            authority.revalidate(context,true);
            var s=owned(context,id);var account=accounts.findByIdForUpdate(s.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
            // This read is a candidate only. Locking Subscription here after Site would
            // invert reservation's operation/FK -> Site order during concurrent calls.
            return new Candidate(s,account,policies.load(context.siteId()).version());
        });
        // Retrieval precedes reservation and runs without any platform transaction or row lock.
        var snapshot=providers.forAccount(candidate.account()).retrieve(new Lookup(candidate.account(),id,candidate.subscription().providerReference(),null));
        var now=clock.instant();var operationId=UUID.randomUUID();
        var proposed=new SubscriptionOperation(operationId,context.siteId(),context.customerId(),id,kind,SubscriptionOperation.Status.RESERVED,
            principal,CreateSubscriptionFingerprint.digest(context.idempotencyKey()),fingerprint(context,id,billId,consent,kind,principal,candidate.subscription().revision(),candidate.policy()),
            "subscription:"+candidate.account().id().value()+":"+operationId+":"+kind,candidate.subscription().revision(),candidate.policy(),candidate.subscription().providerReference(),null,now,now,0);
        try{return tx.requiresNew(()->{
            var claim=operations.claim(proposed);var operation=claim.operation();
            authority.revalidate(context,true);
            var current=owned(context,id);var account=accounts.findByIdForUpdate(current.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
            var s=subscriptions.lock(context.siteId(),id);
            if(!operation.subscriptionId().equals(id)||!operation.customerId().equals(context.customerId())
                ||!operation.fingerprint().equals(fingerprint(context,id,billId,consent,kind,principal,operation.expectedRevision(),operation.policyVersion())))throw SubscriptionRuleErrors.conflict();
            if(!claim.owner()){
                String invoice=null;
                if(kind==SubscriptionOperation.Kind.RECOVER){var bill=bills.lock(context.siteId(),billId);if(!bill.subscriptionId().equals(s.id())||!bill.accountId().equals(account.id()))throw SubscriptionRuleErrors.conflict();invoice=bill.providerInvoiceReference();}
                return new Reserved(operation,s,account,invoice,false);
            }
            var policy=policies.load(context.siteId());
            if(s.revision()!=candidate.subscription().revision()||!s.paymentAccountId().equals(candidate.account().id())||!Objects.equals(s.providerReference(),snapshot.subscriptionReference())
                ||account.status()!=PaymentAccountStatus.ACTIVE||!policy.enabled()||policy.version()!=candidate.policy()
                ||operations.unresolved(context.siteId(),id).stream().anyMatch(o->!o.id().equals(operation.id())))throw SubscriptionRuleErrors.conflict();
            String invoice=null;
            if(kind==SubscriptionOperation.Kind.RECOVER){
                var bill=bills.lock(context.siteId(),billId);invoice=bill.providerInvoiceReference();
                var observed=snapshot.invoices().stream().filter(i->i.reference().equals(bill.providerInvoiceReference())).toList();
                if(observed.size()!=1||observed.getFirst().settlement()!=SettlementKind.OPEN||!observed.getFirst().period().equals(bill.period())
                    ||!observed.getFirst().price().equals(bill.billedPrice()))throw SubscriptionRuleErrors.conflict();
                var disposition=snapshot.recoveryDisposition(invoice);
                // Stripe reservation is local only. Its adapter proves action/pay permission from
                // the complete exact graph after reservation; BLOCKED is never retry authority.
                if(account.provider()==PaymentProviderType.STRIPE||disposition==RecoveryDisposition.ASSOCIATE_EXISTING_ACTION)RecoveryRules.requireActionAssociation(s,bill);
                else RecoveryRules.requireRecoverable(s,bill,disposition!=RecoveryDisposition.RETRY_ALLOWED);
            }else {
                RecoveryRules.requireResumable(s,snapshot.paymentMethodReady());
                if(!consent||snapshot.status()!=Subscription.Status.PAUSED||!s.billingPrice().equals(snapshot.billingPrice()))throw SubscriptionRuleErrors.conflict();
            }
            return new Reserved(operation,s,account,invoice,true);
        });}catch(SubscriptionRuleViolation violation){throw SubscriptionRuleErrors.translate(violation);}
        catch(DataIntegrityViolationException conflict){throw SubscriptionRuleErrors.conflict();}
    }
    public SubscriptionOperationResult readResult(UUID id){return tx.required(()->result(operations.lock(id),null));}
    public void beforeOriginalReplay(SubscriptionCommandContext c,Reserved r){tx.requiresNew(()->{
        var o=operations.lock(r.operation().id());authority.revalidate(c,true);
        var current=owned(c,o.subscriptionId());var a=accounts.findByIdForUpdate(current.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
        var s=subscriptions.lock(c.siteId(),o.subscriptionId());var policy=policies.load(c.siteId());
        if(!o.equals(r.operation())||o.kind()!=SubscriptionOperation.Kind.RECOVER||!o.authorityScope().equals(principal(c))||!a.equals(r.account())
            ||a.provider()!=PaymentProviderType.STRIPE||a.mode()!=PaymentAccountMode.SANDBOX||a.status()!=PaymentAccountStatus.ACTIVE
            ||!Set.of(SubscriptionOperation.Status.PROCESSING,SubscriptionOperation.Status.UNKNOWN).contains(o.status())
            ||!policy.enabled()||policy.version()!=o.policyVersion()||s.revision()!=o.expectedRevision()||!s.nonTerminal()||s.pendingChange()!=null
            ||!s.billingPrice().equals(r.subscription().billingPrice())||s.debt()==null||!recoveryBill(o).filter(s.debt().billingRecordId()::equals).isPresent()
            ||operations.unresolved(c.siteId(),s.id()).stream().anyMatch(other->!other.id().equals(o.id())&&other.kind()!=SubscriptionOperation.Kind.PORTAL&&other.kind()!=SubscriptionOperation.Kind.RECONCILE))throw SubscriptionRuleErrors.conflict();
        return null;
    });}
    public SubscriptionOperationResult recordReply(UUID id,Reply reply){
        return tx.requiresNew(()->{
            var o=operations.lock(id);
            if(o.kind()!=SubscriptionOperation.Kind.RECOVER&&o.kind()!=SubscriptionOperation.Kind.RESUME)throw SubscriptionRuleErrors.conflict();
            sites.findByIdForUpdate(o.siteId()).orElseThrow(SubscriptionRuleErrors::conflict);
            customers.findByIdForUpdate(o.siteId(),o.customerId()).orElseThrow(SubscriptionRuleErrors::conflict);
            var candidate=subscriptions.find(o.siteId(),o.subscriptionId()).orElseThrow(SubscriptionRuleErrors::conflict);
            accounts.findByIdForUpdate(candidate.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
            var s=subscriptions.lock(o.siteId(),o.subscriptionId());
            if(reply.reference()!=null&&!reply.reference().equals(s.providerReference()))throw SubscriptionRuleErrors.conflict();
            if(o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)return result(o,null);
            var status=switch(reply.outcome()){case ACCEPTED->SubscriptionOperation.Status.PROCESSING;case REQUIRES_ACTION->SubscriptionOperation.Status.REQUIRES_ACTION;case UNKNOWN->SubscriptionOperation.Status.UNKNOWN;case DECLINED->SubscriptionOperation.Status.FAILED;};
            var saved=operations.save(new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),status,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),s.providerReference(),status==SubscriptionOperation.Status.FAILED?"PROVIDER_DECLINED":null,o.createdAt(),clock.instant(),o.version()+1),o.version());
            return result(saved,reply.action());
        });
    }
    private Subscription owned(SubscriptionCommandContext c,UUID id){var s=subscriptions.find(c.siteId(),id).orElseThrow(SubscriptionRuleErrors::conflict);if(!s.customerId().equals(c.customerId()))throw SubscriptionRuleErrors.conflict();return s;}
    private static SubscriptionOperationResult result(SubscriptionOperation o,NextAction action){return new SubscriptionOperationResult(o.id(),o.subscriptionId(),o.status(),action);}
    private static String fingerprint(SubscriptionCommandContext c,UUID id,UUID bill,boolean consent,SubscriptionOperation.Kind kind,String principal,long revision,long policy){
        var fields=Arrays.asList(kind,principal,c.siteId(),c.customerId(),c.origin(),id,bill,consent,revision,policy);
        var canonical=new StringBuilder();for(var field:fields){var value=String.valueOf(field);canonical.append(value.length()).append(':').append(value);}
        return "v1."+kind.name().toLowerCase(Locale.ROOT)+"."+(bill==null?"none":bill)+"."+CreateSubscriptionFingerprint.digest(canonical.toString());
    }
    public static Optional<UUID> recoveryBill(SubscriptionOperation o){
        if(o.kind()!=SubscriptionOperation.Kind.RECOVER||!o.fingerprint().matches("v1\\.recover\\.[0-9a-f-]{36}\\.[0-9a-f]{64}"))return Optional.empty();
        try{return Optional.of(UUID.fromString(o.fingerprint().split("\\.")[2]));}catch(IllegalArgumentException invalid){return Optional.empty();}
    }
    static boolean resumeFingerprint(SubscriptionOperation o){return o.kind()==SubscriptionOperation.Kind.RESUME&&o.fingerprint().matches("v1\\.resume\\.none\\.[0-9a-f]{64}");}
    private static String principal(SubscriptionCommandContext c){
        if(c==null||c.actor()==null||c.siteId()==null||c.customerId()==null)throw SubscriptionRuleErrors.conflict();
        return switch(c.actor()){
            case SubscriptionActor.Customer a -> "customer:"+a.session().customerId().value();
            case SubscriptionActor.Partner a -> "partner:"+a.site().keyId().value();
            case SubscriptionActor.Admin a -> "admin:"+a.admin().id().value();
        };
    }
}
