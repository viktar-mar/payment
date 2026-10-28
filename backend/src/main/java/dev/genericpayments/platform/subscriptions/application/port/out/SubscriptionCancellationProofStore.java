package dev.genericpayments.platform.subscriptions.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Exact original cancellation request/result receipts. An ACK alone is never lifecycle proof. */
public interface SubscriptionCancellationProofStore {
    enum Mode { PERIOD_END, IMMEDIATE, WITHDRAW }
    record Receipt(UUID operation, UUID subscription, PaymentAccountId account, UUID step,
                   String reference, Mode mode, Instant boundary, UUID priorOperation, Instant acknowledgedAt) {}

    Optional<Receipt> receipt(PaymentAccountId account, UUID operation);
    Optional<Receipt> activeSchedule(PaymentAccountId account, UUID subscription, Instant boundary);
    void authorize(Subscription subscription, SubscriptionOperation operation);
    Receipt record(Subscription subscription, SubscriptionOperation operation, ProviderOperation step,
                   String owner, Mode mode, Instant boundary, UUID priorOperation, Instant acknowledgedAt);
}
