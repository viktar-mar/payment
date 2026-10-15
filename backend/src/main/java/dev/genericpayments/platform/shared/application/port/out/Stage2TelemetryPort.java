package dev.genericpayments.platform.shared.application.port.out;

import java.time.Duration;
import java.util.Objects;

/** Fixed vocabulary: no caller-controlled meter names or tag maps. */
public interface Stage2TelemetryPort {
    enum Operation {
        CHECKOUT_CREATE("checkouts:create"), CHECKOUT_CONFIRM("checkouts:confirm"), CHECKOUT_CLIENT_SESSION("checkouts:client-session"), PAYMENT_CREATE("payments:create"),
        HANDOFF_ISSUE("handoffs:issue"), HANDOFF_EXCHANGE("handoffs:exchange"), REFUND_CREATE("refunds:create"),
        RECONCILE("payments:reconcile"), CALLBACK_RETRY("callbacks:retry"), FAKE_EVENT("payments:fake-event"),
        CAMPAIGN_CREATE("donation-campaigns:create"), CAMPAIGN_UPDATE("donation-campaigns:update"), CAMPAIGN_STATUS("donation-campaigns:status"),
        DONATION_CHECKOUT_CREATE("donation-checkouts:create"), DONATION_MESSAGE_REDACT("donation-messages:redact");
        private final String scope;
        Operation(String scope) { this.scope = scope; }
        public String scope() { return scope; }
    }
    enum Provider { FAKE, STRIPE }
    enum ProviderOperation { PREPARE, CONFIRM, REFUND, RETRIEVE_PAYMENT, RETRIEVE_REFUND }
    enum ProviderResult { ACCEPTED, PROCESSING, REQUIRES_ACTION, RECOVERABLE_DECLINE, TERMINAL_DECLINE, TEMPORARILY_UNAVAILABLE, UNKNOWN, SUCCEEDED, FAILED, CANCELLED }
    enum PaymentState { CREATED, REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION, PROCESSING, SUCCEEDED, FAILED, CANCELLED, PARTIALLY_REFUNDED, REFUNDED }
    enum InboxOutcome { RECEIVED, DUPLICATE, PROCESSED, FAILED }
    enum OutboxOutcome { DELIVERED, RETRIED, DEAD }
    enum RefundResult { PENDING, PROCESSING, SUCCEEDED, FAILED, UNKNOWN, CANCELLED }
    enum ReplayState { IN_PROGRESS, COMPLETED, EXPIRED_SECRET }

    /** siteKey must be from configured Site data; adapters reject unconfigured labels. */
    record Context(String siteKey, Provider provider) {
        public Context {
            if (siteKey == null || !siteKey.matches("[a-z0-9][a-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("Invalid metric Site");
            Objects.requireNonNull(provider, "provider");
        }
    }
    /** Non-emitting deployment preflight; must run before durable business side effects. */
    void requireContext(Context context);
    void paymentCreated(Context context);
    void paymentTransition(Context context, PaymentState from, PaymentState to);
    void providerCall(Context context, ProviderOperation operation, ProviderResult result, Duration duration);
    void inbox(Context context, InboxOutcome outcome);
    void inboxLag(Context context, Duration lag);
    void outboxPending(Context context, long count);
    void outbox(Context context, OutboxOutcome outcome);
    void outboxLag(Context context, Duration lag);
    void refundRequested(Context context);
    void refundResult(Context context, RefundResult result);
    void idempotencyReplay(Operation operation, ReplayState state);
    void idempotencyConflict(Operation operation);
}
