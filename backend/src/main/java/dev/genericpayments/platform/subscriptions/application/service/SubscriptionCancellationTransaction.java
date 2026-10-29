package dev.genericpayments.platform.subscriptions.application.service;

import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;

@Component
public class SubscriptionCancellationTransaction {
    private final SubscriptionTransactionPort tx;
    private final SubscriptionOperationStore operations;
    private final SubscriptionStore subscriptions;
    private final SubscriptionAuthorityPort authority;
    private final SubscriptionPolicyPort policies;
    private final LockPaymentAccountPort accounts;
    private final LockSitePort sites;
    private final CustomerStore customers;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private TrialEligibilityStore trials;
    @org.springframework.beans.factory.annotation.Autowired private SubscriptionBillingProofStore billingProofs;
    @org.springframework.beans.factory.annotation.Autowired private dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore providerSteps;
    @org.springframework.beans.factory.annotation.Autowired private SubscriptionEventStore events;
    @org.springframework.beans.factory.annotation.Autowired private SubscriptionCallbackPort callbacks;
    public SubscriptionCancellationTransaction(SubscriptionTransactionPort tx,SubscriptionOperationStore operations,SubscriptionStore subscriptions,
        SubscriptionAuthorityPort authority,SubscriptionPolicyPort policies,LockPaymentAccountPort accounts,LockSitePort sites,CustomerStore customers,Clock clock){
        this.tx=tx;this.operations=operations;this.subscriptions=subscriptions;this.authority=authority;this.policies=policies;
        this.accounts=accounts;this.sites=sites;this.customers=customers;this.clock=clock;
    }
    public record Candidate(Subscription subscription,PaymentAccount account,SubscriptionPolicy policy) {}
    public record Reserved(SubscriptionOperation operation,Subscription subscription,PaymentAccount account,boolean owner) {}
    /** Explicitly close only a required trial whose Subscription write was positively never dispatched. */
    public SubscriptionOperationResult abandon(SubscriptionCommandContext c,UUID id,CancelSubscriptionUseCase.Mode mode,Candidate candidate){
        if(mode==null||candidate.account().provider()!=PaymentProviderType.STRIPE||trials==null||billingProofs==null||providerSteps==null)throw SubscriptionRuleErrors.conflict();
        var request=requestHash(c,id,mode,null,SubscriptionOperation.Kind.CANCEL);var now=clock.instant();
        return translated(()->tx.requiresNew(()->{
            // Find the original owner before acquiring Account. No provider Step row lock is taken.
            var eligibility=trials.find(c.siteId(),c.customerId(),candidate.subscription().productId()).orElse(null);
            var original=eligibility==null||eligibility.reservedByOperation()==null?null:operations.lock(eligibility.reservedByOperation());
            // Original-owner serialization precedes replay lookup: a concurrent winner may have
            // closed CREATE and committed this exact CANCEL while we waited for that owner.
            authority.revalidate(c,true);
            var prior=operations.findByRequest(c.siteId(),principal(c),SubscriptionOperation.Kind.CANCEL,CreateSubscriptionFingerprint.digest(c.idempotencyKey()));
            if(prior.isPresent()){
                var replay=operations.lock(prior.orElseThrow().id());
                if(!replay.siteId().equals(c.siteId())||!replay.subscriptionId().equals(id)||!replay.customerId().equals(c.customerId())
                    ||replay.kind()!=SubscriptionOperation.Kind.CANCEL||!replay.authorityScope().equals(principal(c))
                    ||!replay.keyDigest().equals(CreateSubscriptionFingerprint.digest(c.idempotencyKey()))
                    ||!replay.fingerprint().startsWith("v1.cancel.abandon.")||!replay.fingerprint().endsWith("."+request))throw SubscriptionRuleErrors.conflict();
                return result(replay);
            }
            var oid=UUID.randomUUID();
            var proposed=new SubscriptionOperation(oid,c.siteId(),c.customerId(),id,SubscriptionOperation.Kind.CANCEL,SubscriptionOperation.Status.RESERVED,principal(c),
                CreateSubscriptionFingerprint.digest(c.idempotencyKey()),"v1.cancel.abandon."+(original==null?new UUID(0,0):original.id())+"."+request,
                "subscription:"+candidate.account().id().value()+":"+oid+":CANCEL",candidate.subscription().revision(),candidate.policy().version(),null,null,now,now,0);
            var site=sites.findByIdForUpdate(c.siteId()).orElseThrow(SubscriptionRuleErrors::conflict);
            var customer=customers.findByIdForUpdate(c.siteId(),c.customerId()).orElseThrow(SubscriptionRuleErrors::conflict);
            var account=accounts.findByIdForUpdate(candidate.account().id()).orElseThrow(SubscriptionRuleErrors::conflict);
            var s=subscriptions.lock(c.siteId(),id);var policy=policies.load(c.siteId());
            if(original==null||!account.equals(candidate.account())||account.status()!=PaymentAccountStatus.ACTIVE||account.mode()!=PaymentAccountMode.SANDBOX
                ||site.status()!=dev.genericpayments.platform.sites.domain.SiteStatus.ACTIVE||customer.status()!=dev.genericpayments.platform.customers.domain.CustomerStatus.ACTIVE
                ||!s.customerId().equals(c.customerId())||!s.paymentAccountId().equals(account.id())||s.revision()!=candidate.subscription().revision()
                ||!policy.enabled()||policy.version()!=candidate.policy().version()||original.policyVersion()!=policy.version()
                ||s.billingStatus()!=Subscription.Status.INCOMPLETE||s.providerReference()!=null||s.entitledPrice()!=null||s.currentPeriod()!=null
                ||s.paidThrough()!=null||s.trial()!=null||s.debt()!=null||s.pendingChange()!=null||s.cancellation()!=null
                ||s.billingPrice().trialPolicy()!=dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL
                ||original.kind()!=SubscriptionOperation.Kind.CREATE||!CreateSubscriptionFingerprint.startsTrial(original.fingerprint())
                ||!original.siteId().equals(s.siteId())||!original.customerId().equals(s.customerId())||!original.subscriptionId().equals(s.id())
                ||original.providerReference()!=null||Set.of(SubscriptionOperation.Status.SUCCEEDED,SubscriptionOperation.Status.FAILED).contains(original.status())
                ||operations.unresolved(c.siteId(),id).stream().anyMatch(other->!other.id().equals(original.id())&&other.kind()!=SubscriptionOperation.Kind.RECONCILE&&other.kind()!=SubscriptionOperation.Kind.PORTAL))throw SubscriptionRuleErrors.conflict();
            if(!trials.find(s.siteId(),s.customerId(),s.productId()).filter(e->e.consumedAt()==null&&original.id().equals(e.reservedByOperation())).isPresent())throw SubscriptionRuleErrors.conflict();
            var setup=billingProofs.setup(account.id(),original.id()).orElseThrow(SubscriptionRuleErrors::conflict);
            var setupStep=providerSteps.findById(setup.step()).orElseThrow(SubscriptionRuleErrors::conflict);
            if(!setup.account().equals(account.id())||!setup.subscription().equals(s.id())||!setup.operation().equals(original.id())
                ||setupStep.kind()!=dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SETUP_INTENT_CREATE
                ||setupStep.status()!=dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.ACKNOWLEDGED
                ||!setupStep.accountId().equals(account.id())||!setupStep.resourceId().equals(original.id())||!setupStep.providerReference().equals(setup.reference())
                ||!setupStep.operationKey().equals(original.providerOperationKey()+":SETUP")||!setupStep.externalAccountId().equals(account.externalAccountId())||!setupStep.credentialsRef().equals(account.credentialsRef()))throw SubscriptionRuleErrors.conflict();
            var step=providerSteps.find(account.id(),original.providerOperationKey()).orElse(null);
            if(step!=null&&(step.kind()!=dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SUBSCRIPTION_CREATE
                ||!step.resourceId().equals(original.id())||step.status()!=dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.RESERVED
                ||step.firstDispatchedAt()!=null||step.retryBefore()!=null||step.providerReference()!=null||step.leaseOwner()!=null||step.leaseExpiresAt()!=null))throw SubscriptionRuleErrors.conflict();
            operations.save(status(original,SubscriptionOperation.Status.FAILED,"ABANDONED_BEFORE_DISPATCH"),original.version());
            // Keep the single unresolved-owner index intact. Closure and the new local CANCEL
            // claim commit together; any claim/journal/callback failure rolls both back.
            var claim=operations.claim(proposed);var o=claim.operation();
            if(!claim.owner())throw SubscriptionRuleErrors.conflict();
            var closed=subscriptions.save(s.cancel("CUSTOMER_ABANDONED",now),s.revision());
            trials.releaseConfirmedFailure(original.id());
            var done=operations.save(status(o,SubscriptionOperation.Status.SUCCEEDED,null),o.version());
            var event=new SubscriptionEvent(UUID.randomUUID(),s.siteId(),s.id(),s.paymentAccountId(),0,closed.revision(),SubscriptionEvent.Type.CANCELLED,
                SubscriptionEvent.Source.API,now,now,o.id(),null,null,null,"operation:"+o.id()+":CANCELLED",c.requestId());
            if(events.append(event))callbacks.snapshot(closed,event,new SubscriptionAccessPolicy().evaluate(closed,now),now);
            return result(done);
        }));
    }
    public Candidate candidate(SubscriptionCommandContext c,UUID id){
        if(tx.active())throw new IllegalStateException("Provider workflow requires no ambient transaction");
        principal(c);validateKey(c);if(id==null)throw SubscriptionRuleErrors.conflict();
        return translated(()->tx.required(()->{
            authority.revalidate(c,true);
            // Candidate only: do not invert operation/FK -> Site order with a Subscription row lock.
            var s=owned(c,id);var a=accounts.findByIdForUpdate(s.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
            return new Candidate(s,a,policies.load(c.siteId()));
        }));
    }
    public Reserved reserve(SubscriptionCommandContext c,UUID id,CancelSubscriptionUseCase.Mode mode,UUID confirmation,
        Candidate candidate,Snapshot snapshot,boolean safeImmediate,boolean withdraw){
        if(!withdraw&&mode==null)throw SubscriptionRuleErrors.conflict();
        var kind=withdraw?SubscriptionOperation.Kind.WITHDRAW_CANCEL:SubscriptionOperation.Kind.CANCEL;
        var now=clock.instant();var oid=UUID.randomUUID();
        Instant boundary=withdraw?(candidate.subscription().cancellation()==null?null:candidate.subscription().cancellation().effectiveAt()):
            mode==CancelSubscriptionUseCase.Mode.PERIOD_END?liveBoundary(candidate.subscription(),now):null;
        var requestHash=requestHash(c,id,mode,confirmation,kind);
        var fingerprint="v1.cancel."+(withdraw?"withdraw":mode==CancelSubscriptionUseCase.Mode.IMMEDIATE?"immediate":"period_end")+"."
            +(boundary==null?"none":boundary.getEpochSecond()+"/"+boundary.getNano())+"."+requestHash;
        var proposed=new SubscriptionOperation(oid,c.siteId(),c.customerId(),id,kind,SubscriptionOperation.Status.RESERVED,principal(c),
            CreateSubscriptionFingerprint.digest(c.idempotencyKey()),fingerprint,"subscription:"+candidate.account().id().value()+":"+oid+":"+kind,
            candidate.subscription().revision(),candidate.policy().version(),candidate.subscription().providerReference(),null,now,now,0);
        return translated(()->tx.requiresNew(()->{
            var claim=operations.claim(proposed);var o=claim.operation();authority.revalidate(c,true);
            var current=owned(c,id);var a=accounts.findByIdForUpdate(current.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
            var s=subscriptions.lock(c.siteId(),id);
            // Compare only immutable request identity on replay; the original confirmed boundary is retained in the intent.
            if(!id.equals(o.subscriptionId())||!c.customerId().equals(o.customerId())||o.kind()!=kind||!o.fingerprint().endsWith("."+requestHash))throw SubscriptionRuleErrors.conflict();
            if(!claim.owner())return new Reserved(o,s,a,false);
            var policy=policies.load(c.siteId());
            if(s.revision()!=candidate.subscription().revision()||!s.paymentAccountId().equals(candidate.account().id())
                ||a.status()!=PaymentAccountStatus.ACTIVE||!policy.enabled()||policy.version()!=candidate.policy().version()
                ||!Objects.equals(s.providerReference(),snapshot.subscriptionReference())||snapshot.status()==Subscription.Status.CANCELLED
                ||!s.nonTerminal()||s.pendingChange()!=null
                ||operations.unresolved(c.siteId(),id).stream().anyMatch(other->!other.id().equals(proposed.id())
                    &&other.kind()!=SubscriptionOperation.Kind.RECONCILE&&other.kind()!=SubscriptionOperation.Kind.PORTAL))throw SubscriptionRuleErrors.conflict();
            if(withdraw){
                SubscriptionCancellationRules.requireWithdrawable(s,clock.instant());
                if(!s.cancellation().effectiveAt().equals(snapshot.cancellationAt()))throw SubscriptionRuleErrors.conflict();
            }else if(mode==CancelSubscriptionUseCase.Mode.IMMEDIATE){
                boolean inactive=Set.of(Subscription.Status.PAUSED,Subscription.Status.UNPAID,Subscription.Status.INCOMPLETE).contains(s.billingStatus())
                    &&(s.paidThrough()==null||!clock.instant().isBefore(s.paidThrough()))&&(s.trial()==null||!clock.instant().isBefore(s.trial().end()));
                if(!safeImmediate||!SubscriptionCancellationRules.allowImmediate(s,policy,clock.instant())||!inactive&&!id.equals(confirmation))throw SubscriptionRuleErrors.conflict();
            }else {
                if(boundary==null||!boundary.equals(liveBoundary(s,clock.instant()))||!boundary.equals(providerBoundary(snapshot,clock.instant()))
                    ||s.cancellation()!=null||snapshot.cancellationAt()!=null)throw SubscriptionRuleErrors.conflict();
            }
            // Reservation commits before dispatch. It changes no lifecycle/access/cash projection.
            o=operations.save(status(o,SubscriptionOperation.Status.PROCESSING,null),o.version());
            return new Reserved(o,s,a,true);
        }));
    }
    public SubscriptionOperationResult reply(Reserved r,Reply reply){return tx.requiresNew(()->{
        var o=operations.lock(r.operation().id());
        sites.findByIdForUpdate(o.siteId()).orElseThrow();customers.findByIdForUpdate(o.siteId(),o.customerId()).orElseThrow();
        var current=subscriptions.find(o.siteId(),o.subscriptionId()).orElseThrow();accounts.findByIdForUpdate(current.paymentAccountId()).orElseThrow();
        var s=subscriptions.lock(o.siteId(),o.subscriptionId());
        if(o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)return result(o);
        if(reply.reference()!=null&&!reply.reference().equals(s.providerReference()))throw SubscriptionRuleErrors.conflict();
        // Acceptance, rejection receipts and timeouts are not lifecycle proof; scoped facts finish the intent.
        var saved=operations.save(status(o,reply.outcome()==Outcome.UNKNOWN?SubscriptionOperation.Status.UNKNOWN:SubscriptionOperation.Status.PROCESSING,null),o.version());
        return result(saved);
    });}
    public SubscriptionOperationResult result(Reserved r){return tx.required(()->result(operations.lock(r.operation().id())));}
    /** Final local fence for an explicit same-command replay; it grants no provider result proof. */
    public void beforeOriginalReplay(SubscriptionCommandContext c,Reserved r,Snapshot snapshot){translated(()->tx.requiresNew(()->{
        var o=operations.lock(r.operation().id());authority.revalidate(c,true);
        var current=owned(c,o.subscriptionId());var a=accounts.findByIdForUpdate(current.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
        var s=subscriptions.lock(c.siteId(),o.subscriptionId());var policy=policies.load(c.siteId());
        if(!o.equals(r.operation())||!o.authorityScope().equals(principal(c))||!a.equals(r.account())
            ||a.provider()!=PaymentProviderType.STRIPE||a.mode()!=PaymentAccountMode.SANDBOX||a.status()!=PaymentAccountStatus.ACTIVE
            ||!Set.of(SubscriptionOperation.Status.PROCESSING,SubscriptionOperation.Status.UNKNOWN).contains(o.status())
            ||!policy.enabled()||policy.version()!=o.policyVersion()||s.revision()!=o.expectedRevision()||!s.nonTerminal()||s.pendingChange()!=null
            ||!s.paymentAccountId().equals(a.id())||!s.billingPrice().equals(r.subscription().billingPrice())
            ||!Objects.equals(s.providerReference(),snapshot.subscriptionReference())||snapshot.status()==Subscription.Status.CANCELLED
            ||operations.unresolved(c.siteId(),s.id()).stream().anyMatch(other->!other.id().equals(o.id())&&other.kind()!=SubscriptionOperation.Kind.RECONCILE&&other.kind()!=SubscriptionOperation.Kind.PORTAL))throw SubscriptionRuleErrors.conflict();
        if(!immediate(o)){
            var boundary=boundary(o).orElseThrow(SubscriptionRuleErrors::conflict);
            if(!clock.instant().isBefore(boundary))throw SubscriptionRuleErrors.conflict();
            if(o.kind()==SubscriptionOperation.Kind.WITHDRAW_CANCEL){
                SubscriptionCancellationRules.requireWithdrawable(s,clock.instant());
                if(!boundary.equals(s.cancellation().effectiveAt())||snapshot.cancellationAt()!=null&&!boundary.equals(snapshot.cancellationAt()))throw SubscriptionRuleErrors.conflict();
            }else if(o.kind()!=SubscriptionOperation.Kind.CANCEL||s.cancellation()!=null||!boundary.equals(liveBoundary(s,clock.instant()))
                ||!boundary.equals(providerBoundary(snapshot,clock.instant()))||snapshot.cancellationAt()!=null&&!boundary.equals(snapshot.cancellationAt()))throw SubscriptionRuleErrors.conflict();
        }else if(!SubscriptionCancellationRules.allowImmediate(s,policy,clock.instant()))throw SubscriptionRuleErrors.conflict();
        return null;
    }));}
    static Instant liveBoundary(Subscription s,Instant now){
        return SubscriptionCancellationRules.liveBoundary(s,now);
    }
    private static Instant providerBoundary(Snapshot s,Instant now){
        Instant trial=s.status()==Subscription.Status.TRIALING&&s.trial()!=null&&!now.isBefore(s.trial().start())&&now.isBefore(s.trial().end())?s.trial().end():null;
        Instant paid=s.invoices().stream().filter(i->i.settlement()==SettlementKind.CASH||i.settlement()==SettlementKind.ZERO)
            .filter(i->i.price().equals(s.billingPrice())&&i.period().equals(s.period())&&!now.isBefore(i.period().start())&&now.isBefore(i.period().end()))
            .map(i->i.period().end()).min(Comparator.naturalOrder()).orElse(null);
        return paid==null?trial:trial==null?paid:paid.isBefore(trial)?paid:trial;
    }
    public static Optional<Instant> boundary(SubscriptionOperation o){
        if(!valid(o))return Optional.empty();var value=o.fingerprint().split("\\.")[3];if(value.equals("none"))return Optional.empty();
        try{var parts=value.split("/");return Optional.of(Instant.ofEpochSecond(Long.parseLong(parts[0]),Long.parseLong(parts[1])));}catch(RuntimeException invalid){return Optional.empty();}
    }
    public static boolean immediate(SubscriptionOperation o){return valid(o)&&o.kind()==SubscriptionOperation.Kind.CANCEL&&o.fingerprint().split("\\.")[2].equals("immediate");}
    private static boolean valid(SubscriptionOperation o){return o.fingerprint().matches("v1\\.cancel\\.(immediate|period_end|withdraw)\\.(none|-?[0-9]+/[0-9]+)\\.[a-f0-9]{64}");}
    private static String requestHash(SubscriptionCommandContext c,UUID id,CancelSubscriptionUseCase.Mode mode,UUID confirmation,SubscriptionOperation.Kind kind){
        var value=new StringBuilder();for(Object field:Arrays.asList(kind,principal(c),c.siteId(),c.customerId(),c.origin(),id,mode,confirmation)){
            var text=String.valueOf(field);value.append(text.length()).append(':').append(text);
        }return CreateSubscriptionFingerprint.digest(value.toString());
    }
    private Subscription owned(SubscriptionCommandContext c,UUID id){var s=subscriptions.find(c.siteId(),id).orElseThrow(SubscriptionRuleErrors::conflict);if(!s.customerId().equals(c.customerId()))throw SubscriptionRuleErrors.conflict();return s;}
    private SubscriptionOperation status(SubscriptionOperation o,SubscriptionOperation.Status status,String failure){return new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),status,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),failure,o.createdAt(),clock.instant(),o.version()+1);}
    private static SubscriptionOperationResult result(SubscriptionOperation o){return new SubscriptionOperationResult(o.id(),o.subscriptionId(),o.status(),null);}
    private static String principal(SubscriptionCommandContext c){if(c==null||c.actor()==null||c.siteId()==null||c.customerId()==null)throw SubscriptionRuleErrors.conflict();return switch(c.actor()){case SubscriptionActor.Customer a->"customer:"+a.session().customerId().value();case SubscriptionActor.Partner a->"partner:"+a.site().keyId().value();case SubscriptionActor.Admin a->"admin:"+a.admin().id().value();};}
    private static void validateKey(SubscriptionCommandContext c){if(c.idempotencyKey()==null||c.idempotencyKey().isBlank()||c.idempotencyKey().length()>255||c.idempotencyKey().codePoints().anyMatch(Character::isISOControl))throw SubscriptionRuleErrors.conflict();}
    private static <T>T translated(Supplier<T> work){try{return work.get();}catch(SubscriptionRuleViolation e){throw SubscriptionRuleErrors.translate(e);}catch(DataIntegrityViolationException e){throw SubscriptionRuleErrors.conflict();}}
}
