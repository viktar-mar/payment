package dev.genericpayments.platform.subscriptions.domain;
import java.time.Instant;
import java.util.Set;
public final class PlanChangeRules {
    private PlanChangeRules() {}
    public static void requireQuoteCurrent(SubscriptionQuote quote,long revision,long policyVersion,Instant now) {
        if(quote.subscriptionRevision()!=revision||quote.policyVersion()!=policyVersion||!now.isBefore(quote.expiresAt()))
            throw new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.QUOTE_STALE,"Preview again before changing plan");
    }
    public static void requireChangeable(Subscription s,boolean unresolved) {
        if(s.billingStatus()!=Subscription.Status.ACTIVE||s.paidThrough()==null||s.entitledPrice()==null||s.currentPeriod()==null
            ||s.debt()!=null||s.cancellation()!=null||s.pendingChange()!=null||unresolved)
            throw new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.STATE_CONFLICT,"Plan cannot change in this state");
    }
    public static Set<ChangeTiming> allowedTiming(PlanTransition transition,SubscriptionPriceTerms current,SubscriptionPriceTerms target,SubscriptionPolicy policy,boolean conditionalUpdate){
        if(transition==null||!policy.enabled()||!transition.siteId().equals(policy.siteId())
            ||!transition.productId().equals(current.productId())||!transition.productId().equals(target.productId())
            ||!transition.fromPriceId().equals(current.priceId())||!transition.toPriceId().equals(target.priceId())
            ||!current.regularAmount().currency().equals(target.regularAmount().currency()))return Set.of();
        return transition.direction()==PlanTransition.Direction.UPGRADE&&transition.immediateAllowed()&&!policy.deferUpgrades()
            &&current.interval()==target.interval()&&conditionalUpdate?Set.of(ChangeTiming.IMMEDIATE,ChangeTiming.NEXT_PERIOD):Set.of(ChangeTiming.NEXT_PERIOD);
    }
}
