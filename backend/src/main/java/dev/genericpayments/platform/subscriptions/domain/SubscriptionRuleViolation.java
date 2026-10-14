package dev.genericpayments.platform.subscriptions.domain;

import java.util.Objects;

public final class SubscriptionRuleViolation extends RuntimeException {
    public enum Reason { STATE_CONFLICT, QUOTE_STALE }
    private final Reason reason;

    public SubscriptionRuleViolation(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason reason() { return reason; }
}
