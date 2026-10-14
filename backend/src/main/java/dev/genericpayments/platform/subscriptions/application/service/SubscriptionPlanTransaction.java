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
public class SubscriptionPlanTransaction {
    private final SubscriptionTransactionPort tx;private final SubscriptionOperationStore operations;private final SubscriptionStore subscriptions;
    private final SubscriptionQuoteStore quotes;private final SubscriptionAuthorityPort authority;private final SubscriptionPolicyPort policies;
    private final SubscriptionCatalogPort catalog;private final LockPaymentAccountPort accounts;private final LockSitePort sites;
    private final CustomerStore customers;private final SubscriptionEventStore events;private final SubscriptionCallbackPort callbacks;private final Clock clock;
    public SubscriptionPlanTransaction(SubscriptionTransactionPort tx,SubscriptionOperationStore operations,SubscriptionStore subscriptions,
        SubscriptionQuoteStore quotes,SubscriptionAuthorityPort authority,SubscriptionPolicyPort policies,SubscriptionCatalogPort catalog,
        LockPaymentAccountPort accounts,LockSitePort sites,CustomerStore customers,SubscriptionEventStore events,SubscriptionCallbackPort callbacks,Clock clock){
        this.tx=tx;this.operations=operations;this.subscriptions=subscriptions;this.quotes=quotes;this.authority=authority;this.policies=policies;this.catalog=catalog;
        this.accounts=accounts;this.sites=sites;this.customers=customers;this.events=events;this.callbacks=callbacks;this.clock=clock;
    }
    public record Candidate(Subscription subscription,PaymentAccount account,SubscriptionPolicy policy) {}
    public record Preview(Candidate candidate,SubscriptionPriceTerms target,ChangeTiming timing,SubscriptionOperation request) {}
    public record Reserved(SubscriptionOperation operation,Subscription subscription,PaymentAccount account,SubscriptionQuote quote,boolean owner) {}
    public void outside(){if(tx.active())throw new IllegalStateException("Provider workflow requires no ambient transaction");}
    // Candidate reads must not take Subscription FOR UPDATE behind the Site lock: an operation
    // claim already holds the Subscription FK key-share lock before it acquires Site. Fresh
    // mutation reservation below claims first, then revalidates authority and revision under locks.
    public Candidate candidate(SubscriptionCommandContext c,UUID id){outside();principal(c);return translated(()->tx.required(()->{
        authority.revalidate(c,true);var s=owned(c,id);var a=accounts.findByIdForUpdate(s.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
        return new Candidate(s,a,policies.load(c.siteId()));
    }));}
    public Preview preview(PreviewSubscriptionChangeUseCase.Query query,Candidate candidate,boolean conditional){return translated(()->tx.required(()->{
        var c=query.context();authority.revalidate(c,true);var s=owned(c,query.subscriptionId());var policy=policies.load(c.siteId());
        if(s.revision()!=candidate.subscription().revision()||!s.paymentAccountId().equals(candidate.account().id()))throw stale();
        changeable(s,operations.hasUnresolved(c.siteId(),s.id()));
        if(candidate.account().status()!=PaymentAccountStatus.ACTIVE)throw SubscriptionRuleErrors.conflict();
        var target=catalog.published(c.siteId(),query.targetPriceId());var allowed=allowed(s,target,policy,conditional);
        if(query.timing()==null||allowed.isEmpty())throw SubscriptionRuleErrors.conflict();
        var timing=allowed.contains(query.timing())?query.timing():ChangeTiming.NEXT_PERIOD;
        var now=clock.instant();var id=UUID.randomUUID();
        var request=new SubscriptionOperation(id,c.siteId(),c.customerId(),s.id(),SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Status.RESERVED,principal(c),"preview", "preview","preview:"+id,s.revision(),policy.version(),s.providerReference(),null,now,now,0);
        return new Preview(new Candidate(s,candidate.account(),policy),target,timing,request);
    }));}
    public SubscriptionQuote savePreview(PreviewSubscriptionChangeUseCase.Query query,Preview p,ProviderQuote provider,ChangeTiming timing,boolean conditional){return translated(()->tx.required(()->{
        var c=query.context();authority.revalidate(c,true);var s=owned(c,query.subscriptionId());var policy=policies.load(c.siteId());
        var account=accounts.findByIdForUpdate(s.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
        if(s.revision()!=p.candidate().subscription().revision()||policy.version()!=p.candidate().policy().version()||account.status()!=PaymentAccountStatus.ACTIVE)throw stale();
        changeable(s,operations.hasUnresolved(c.siteId(),s.id()));
        if(!catalog.published(c.siteId(),p.target().priceId()).equals(p.target())||!allowed(s,p.target(),policy,conditional).contains(timing))throw stale();
        var now=clock.instant();
        if(provider==null||provider.expiresAt()==null||!now.isBefore(provider.expiresAt())||provider.expiresAt().isAfter(now.plusSeconds(300))
            ||provider.effectiveAt()==null||timing==ChangeTiming.IMMEDIATE&&(provider.dueNowMinor()<0||provider.effectiveAt().isAfter(now))
            ||timing==ChangeTiming.NEXT_PERIOD&&(!provider.effectiveAt().equals(s.currentPeriod().end())||provider.dueNowMinor()!=0))throw stale();
        var quote=new SubscriptionQuote(UUID.randomUUID(),c.siteId(),s.id(),scope(c),s.billingPrice().priceId(),s.billingPrice().version(),p.target(),timing,
            provider.dueNowMinor(),provider.nextAmountMinor(),provider.currency(),provider.effectiveAt(),provider.expiresAt(),provider.taxFinal(),s.revision(),policy.version(),provider.reference());
        quotes.save(quote);return quote;
    }));}
    public Reserved reserve(SubscriptionCommandContext c,UUID id,UUID quoteId,Candidate candidate,boolean conditional,boolean clear){
        outside();var principal=principal(c);validateKey(c);
        var kind=clear?SubscriptionOperation.Kind.CLEAR_CHANGE:SubscriptionOperation.Kind.CHANGE;var now=clock.instant();var oid=UUID.randomUUID();
        var proposed=new SubscriptionOperation(oid,c.siteId(),c.customerId(),id,kind,SubscriptionOperation.Status.RESERVED,principal,
            CreateSubscriptionFingerprint.digest(c.idempotencyKey()),fingerprint(c,id,quoteId,kind),"subscription:"+candidate.account().id().value()+":"+oid+":"+kind,
            candidate.subscription().revision(),candidate.policy().version(),candidate.subscription().providerReference(),null,now,now,0);
        return translated(()->tx.requiresNew(()->{
            var claim=operations.claim(proposed);var o=claim.operation();authority.revalidate(c,true);
            var current=owned(c,id);var account=accounts.findByIdForUpdate(current.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);
            var s=subscriptions.lock(c.siteId(),id);
            if(!id.equals(o.subscriptionId())||!c.customerId().equals(o.customerId())||!o.fingerprint().equals(proposed.fingerprint()))throw SubscriptionRuleErrors.conflict();
            if(!claim.owner())return new Reserved(o,s,account,quotes.forOperation(c.siteId(),id,o.id()).orElse(null),false);
            var policy=policies.load(c.siteId());
            if(s.revision()!=candidate.subscription().revision()||policy.version()!=candidate.policy().version()||!policy.enabled()
                ||!s.paymentAccountId().equals(candidate.account().id())||account.status()!=PaymentAccountStatus.ACTIVE
                ||operations.unresolved(c.siteId(),id).stream().anyMatch(other->!other.id().equals(o.id())))throw SubscriptionRuleErrors.conflict();
            SubscriptionQuote quote;
            if(clear){
                if(s.pendingChange()==null||s.pendingChange().state()!=Subscription.ChangeState.SCHEDULED||s.cancellation()!=null
                    ||!s.nonTerminal()||!clock.instant().isBefore(s.pendingChange().effectiveAt()))throw SubscriptionRuleErrors.conflict();
                quote=quotes.forOperation(c.siteId(),id,s.pendingChange().operationId()).orElseThrow(SubscriptionRuleErrors::conflict);
                if(!quote.target().equals(s.pendingChange().target())||!quote.effectiveAt().equals(s.pendingChange().effectiveAt()))throw SubscriptionRuleErrors.conflict();
            }else{
                quote=quotes.lock(c.siteId(),quoteId);validateQuote(c,s,quote,policy,conditional);changeable(s,false);
            }
            quotes.bind(o,quote);
            if(!clear){
                var updated=withChange(s,new Subscription.Change(o.id(),quote.target(),quote.effectiveAt(),Subscription.ChangeState.UNKNOWN),clock.instant());
                subscriptions.save(updated,s.revision());append(updated,o,SubscriptionEvent.Type.CHANGE_REQUESTED,c.requestId());
            }
            return new Reserved(o,s,account,quote,true);
        }));
    }
    /** Final locked revalidation after the external quote read, before any mutation is dispatched. */
    public void beforeMutation(SubscriptionCommandContext c,Reserved r,boolean conditional){translated(()->tx.requiresNew(()->{
        var o=operations.lock(r.operation().id());authority.revalidate(c,true);
        var account=accounts.findByIdForUpdate(r.account().id()).orElseThrow(SubscriptionRuleErrors::conflict);var s=subscriptions.lock(c.siteId(),r.subscription().id());var policy=policies.load(c.siteId());
        if(o.status()!=SubscriptionOperation.Status.RESERVED||account.status()!=PaymentAccountStatus.ACTIVE||!policy.enabled()||policy.version()!=o.policyVersion())throw stale();
        if(o.kind()==SubscriptionOperation.Kind.CHANGE){
            if(s.revision()!=o.expectedRevision()+1||s.pendingChange()==null||!s.pendingChange().operationId().equals(o.id())
                ||!s.billingPrice().equals(r.subscription().billingPrice())||!s.pendingChange().target().equals(r.quote().target()))throw stale();
            validateQuote(c,r.subscription(),r.quote(),policy,conditional);
        }else if(s.revision()!=o.expectedRevision()||s.pendingChange()==null||s.pendingChange().state()!=Subscription.ChangeState.SCHEDULED
            ||!s.pendingChange().equals(r.subscription().pendingChange())||!clock.instant().isBefore(s.pendingChange().effectiveAt()))throw stale();
        operations.save(status(o,SubscriptionOperation.Status.PROCESSING,null),o.version());return null;
    }));}
    /** No provider mutation was dispatched: release only this reservation's pending change. */
    public void rejectBeforeMutation(Reserved r){tx.requiresNew(()->{
        var o=operations.lock(r.operation().id());lockLifecycle(o);var s=subscriptions.lock(o.siteId(),o.subscriptionId());
        if(o.status()!=SubscriptionOperation.Status.RESERVED)return null;
        operations.save(status(o,SubscriptionOperation.Status.FAILED,"QUOTE_STALE"),o.version());
        if(s.pendingChange()!=null&&s.pendingChange().operationId().equals(o.id()))subscriptions.save(withChange(s,null,clock.instant()),s.revision());return null;
    });}
    public SubscriptionOperationResult reply(Reserved r,Reply reply){return tx.requiresNew(()->{
        var o=operations.lock(r.operation().id());lockLifecycle(o);var s=subscriptions.lock(o.siteId(),o.subscriptionId());
        if(o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)return result(o,null);
        if(reply.reference()!=null&&!reply.reference().equals(s.providerReference()))throw SubscriptionRuleErrors.conflict();
        // A receipt is not authoritative rejection or application proof. Scoped facts finish both paths.
        var next=switch(reply.outcome()){case UNKNOWN->SubscriptionOperation.Status.UNKNOWN;case REQUIRES_ACTION->SubscriptionOperation.Status.REQUIRES_ACTION;default->SubscriptionOperation.Status.PROCESSING;};
        var saved=operations.save(status(o,next,null),o.version());
        if(s.pendingChange()!=null&&s.pendingChange().operationId().equals(o.id())&&next==SubscriptionOperation.Status.REQUIRES_ACTION)
            subscriptions.save(withChange(s,new Subscription.Change(o.id(),r.quote().target(),r.quote().effectiveAt(),Subscription.ChangeState.PAYMENT_PENDING),clock.instant()),s.revision());
        return result(saved,reply.action());
    });}
    public SubscriptionOperationResult result(Reserved r){return tx.required(()->result(operations.lock(r.operation().id()),null));}
    private void validateQuote(SubscriptionCommandContext c,Subscription s,SubscriptionQuote q,SubscriptionPolicy policy,boolean conditional){
        if(!q.siteId().equals(c.siteId())||!q.subscriptionId().equals(s.id())||!q.authorityScope().equals(scope(c)))throw SubscriptionRuleErrors.conflict();
        PlanChangeRules.requireQuoteCurrent(q,s.revision(),policy.version(),clock.instant());
        if(!q.fromPriceId().equals(s.billingPrice().priceId())||q.fromPriceVersion()!=s.billingPrice().version()||!catalog.published(c.siteId(),q.target().priceId()).equals(q.target())
            ||!allowed(s,q.target(),policy,conditional).contains(q.timing())||q.timing()==ChangeTiming.IMMEDIATE&&q.dueNowMinor()<0
            ||!clock.instant().isBefore(s.paidThrough()))throw stale();
    }
    private Set<ChangeTiming> allowed(Subscription s,SubscriptionPriceTerms target,SubscriptionPolicy policy,boolean conditional){return PlanChangeRules.allowedTiming(policies.transition(s.siteId(),s.productId(),s.billingPrice().priceId(),target.priceId()).orElse(null),s.billingPrice(),target,policy,conditional);}
    private void changeable(Subscription s,boolean unresolved){PlanChangeRules.requireChangeable(s,unresolved);if(!clock.instant().isBefore(s.paidThrough())||clock.instant().isBefore(s.currentPeriod().start()))throw SubscriptionRuleErrors.conflict();}
    private Subscription owned(SubscriptionCommandContext c,UUID id){var s=subscriptions.find(c.siteId(),id).orElseThrow(SubscriptionRuleErrors::conflict);if(!s.customerId().equals(c.customerId()))throw SubscriptionRuleErrors.conflict();return s;}
    private void lockLifecycle(SubscriptionOperation o){sites.findByIdForUpdate(o.siteId()).orElseThrow();customers.findByIdForUpdate(o.siteId(),o.customerId()).orElseThrow();var s=subscriptions.find(o.siteId(),o.subscriptionId()).orElseThrow();accounts.findByIdForUpdate(s.paymentAccountId()).orElseThrow();}
    private SubscriptionOperation status(SubscriptionOperation o,SubscriptionOperation.Status status,String failure){return new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),status,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),failure,o.createdAt(),clock.instant(),o.version()+1);}
    private static SubscriptionOperationResult result(SubscriptionOperation o,NextAction a){return new SubscriptionOperationResult(o.id(),o.subscriptionId(),o.status(),a);}
    static Subscription withChange(Subscription s,Subscription.Change change,Instant at){return new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),change,s.debt(),s.terminalReason(),s.revision()+1,s.createdAt(),at,s.lastReconciledAt());}
    private void append(Subscription s,SubscriptionOperation o,SubscriptionEvent.Type type,String request){var now=clock.instant();var e=new SubscriptionEvent(UUID.randomUUID(),s.siteId(),s.id(),s.paymentAccountId(),0,s.revision(),type,SubscriptionEvent.Source.API,now,now,o.id(),null,null,s.providerReference(),"operation:"+o.id()+":"+type,request);if(events.append(e))callbacks.snapshot(s,e,new SubscriptionAccessPolicy().evaluate(s,now),now);}
    static String scope(SubscriptionCommandContext c){return CreateSubscriptionFingerprint.digest("plan:"+c.siteId().value()+":"+c.customerId().value()+":"+principal(c));}
    private static String principal(SubscriptionCommandContext c){if(c==null||c.actor()==null||c.siteId()==null||c.customerId()==null)throw SubscriptionRuleErrors.conflict();return switch(c.actor()){case SubscriptionActor.Customer a->"customer:"+a.session().customerId().value();case SubscriptionActor.Partner a->"partner:"+a.site().keyId().value();case SubscriptionActor.Admin a->"admin:"+a.admin().id().value();};}
    private static String fingerprint(SubscriptionCommandContext c,UUID id,UUID quote,SubscriptionOperation.Kind kind){var value=new StringBuilder();for(Object field:Arrays.asList(kind,principal(c),c.siteId(),c.customerId(),c.origin(),id,quote)){var text=String.valueOf(field);value.append(text.length()).append(':').append(text);}return "v1.plan."+CreateSubscriptionFingerprint.digest(value.toString());}
    private static void validateKey(SubscriptionCommandContext c){if(c.idempotencyKey()==null||c.idempotencyKey().isBlank()||c.idempotencyKey().length()>255||c.idempotencyKey().codePoints().anyMatch(Character::isISOControl))throw SubscriptionRuleErrors.conflict();}
    private static dev.genericpayments.platform.shared.application.PaymentApplicationException stale(){return SubscriptionRuleErrors.translate(new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.QUOTE_STALE,"Preview again"));}
    private static <T>T translated(Supplier<T> work){try{return work.get();}catch(SubscriptionRuleViolation e){throw SubscriptionRuleErrors.translate(e);}catch(DataIntegrityViolationException e){throw SubscriptionRuleErrors.conflict();}}
}
