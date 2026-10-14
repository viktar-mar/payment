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
    public SubscriptionCancellationTransaction(SubscriptionTransactionPort tx,SubscriptionOperationStore operations,SubscriptionStore subscriptions,
        SubscriptionAuthorityPort authority,SubscriptionPolicyPort policies,LockPaymentAccountPort accounts,LockSitePort sites,CustomerStore customers,Clock clock){
        this.tx=tx;this.operations=operations;this.subscriptions=subscriptions;this.authority=authority;this.policies=policies;
        this.accounts=accounts;this.sites=sites;this.customers=customers;this.clock=clock;
    }
    public record Candidate(Subscription subscription,PaymentAccount account,SubscriptionPolicy policy) {}
    public record Reserved(SubscriptionOperation operation,Subscription subscription,PaymentAccount account,boolean owner) {}
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
    static Optional<Instant> boundary(SubscriptionOperation o){
        if(!valid(o))return Optional.empty();var value=o.fingerprint().split("\\.")[3];if(value.equals("none"))return Optional.empty();
        try{var parts=value.split("/");return Optional.of(Instant.ofEpochSecond(Long.parseLong(parts[0]),Long.parseLong(parts[1])));}catch(RuntimeException invalid){return Optional.empty();}
    }
    static boolean immediate(SubscriptionOperation o){return valid(o)&&o.kind()==SubscriptionOperation.Kind.CANCEL&&o.fingerprint().split("\\.")[2].equals("immediate");}
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
