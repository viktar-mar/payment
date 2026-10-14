package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionRuleViolation;
public final class SubscriptionRuleErrors {
    private SubscriptionRuleErrors() {}
    public static PaymentApplicationException translate(SubscriptionRuleViolation failure) {
        return switch(failure.reason()) {
            case STATE_CONFLICT -> conflict();
            case QUOTE_STALE -> new PaymentApplicationException("SUBSCRIPTION_QUOTE_STALE",409,"Preview again before changing plan");
        };
    }
    public static PaymentApplicationException conflict(){return new PaymentApplicationException("SUBSCRIPTION_STATE_CONFLICT",409,"Subscription action unavailable");}
}
