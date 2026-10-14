package dev.genericpayments.platform.subscriptions.domain;
public final class RecoveryRules {
    private RecoveryRules() {}
    public static void requireRecoverable(Subscription s,BillingRecord bill,boolean liveAttempt) {
        requireDebt(s,bill);
        if(liveAttempt)throw conflict();
    }
    /** Association continues the exact existing attempt; it never authorizes another attempt. */
    public static void requireActionAssociation(Subscription s,BillingRecord bill) {requireDebt(s,bill);}
    private static void requireDebt(Subscription s,BillingRecord bill) {
        if(!s.nonTerminal()||s.debt()==null||!s.debt().billingRecordId().equals(bill.id())
            ||!s.id().equals(bill.subscriptionId())||!s.siteId().equals(bill.siteId())||!s.paymentAccountId().equals(bill.accountId())
            ||bill.settlement()!=BillingRecord.Settlement.OPEN)throw conflict();
    }
    public static void requireResumable(Subscription s,boolean methodReady) {
        if(s.billingStatus()!=Subscription.Status.PAUSED||!methodReady||s.pendingChange()!=null)throw conflict();
    }
    private static SubscriptionRuleViolation conflict(){return new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.STATE_CONFLICT,"Recovery unavailable");}
}
