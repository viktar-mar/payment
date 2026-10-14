package dev.genericpayments.platform.subscriptions.domain;
import java.time.Instant;
public final class SubscriptionCancellationRules {
    private SubscriptionCancellationRules() {}
    /** Authoritative nonfinancial boundary shared by cancellation admission and advertised actions. */
    public static Instant liveBoundary(Subscription s,Instant now){
        Instant paid=s.paidThrough()!=null&&now.isBefore(s.paidThrough())&&s.currentPeriod()!=null&&!now.isBefore(s.currentPeriod().start())?s.paidThrough():null;
        Instant trial=s.trial()!=null&&!now.isBefore(s.trial().start())&&now.isBefore(s.trial().end())?s.trial().end():null;
        return paid==null?trial:trial==null?paid:paid.isBefore(trial)?paid:trial;
    }
    public static boolean allowImmediate(Subscription s,SubscriptionPolicy p,Instant now){
        boolean livePaid=s.paidThrough()!=null&&now.isBefore(s.paidThrough());
        boolean liveTrial=s.trial()!=null&&now.isBefore(s.trial().end());
        boolean inactive=java.util.Set.of(Subscription.Status.PAUSED,Subscription.Status.UNPAID,Subscription.Status.INCOMPLETE).contains(s.billingStatus());
        return inactive&&!livePaid&&!liveTrial||p.allowImmediateCancellation();
    }
    public static void requireWithdrawable(Subscription s,Instant now) {
        if(!s.nonTerminal()||s.cancellation()==null||!now.isBefore(s.cancellation().effectiveAt()))
            throw new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.STATE_CONFLICT,"Cancellation can no longer be withdrawn");
    }
}
