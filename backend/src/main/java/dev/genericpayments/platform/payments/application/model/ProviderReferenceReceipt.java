package dev.genericpayments.platform.payments.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import java.time.Instant;
import java.util.Objects;

/** Only typed references cross admission; provider snapshots and secrets never do. */
public record ProviderReferenceReceipt(PaymentProviderType provider, PaymentAccountId accountId,
        String eventId, String objectId, ObjectType objectType, String providerEventType,
        String apiVersion, Instant occurredAt, Instant receivedAt) {
    public enum ObjectType { PAYMENT, REFUND }

    public ProviderReferenceReceipt {
        Objects.requireNonNull(accountId);Objects.requireNonNull(objectType);
        Objects.requireNonNull(occurredAt);Objects.requireNonNull(receivedAt);
        if (provider != PaymentProviderType.STRIPE || eventId == null || !eventId.matches("evt_[A-Za-z0-9]{1,251}")
                || objectId == null || !objectId.matches(objectType == ObjectType.PAYMENT ? "pi_[A-Za-z0-9]{1,252}" : "re_[A-Za-z0-9]{1,252}")
                || !"2026-09-30.endive".equals(apiVersion)
                || !(objectType == ObjectType.PAYMENT
                    ? java.util.Set.of("payment_intent.processing","payment_intent.requires_action","payment_intent.succeeded","payment_intent.payment_failed","payment_intent.canceled")
                    : java.util.Set.of("refund.created","refund.updated","refund.failed")).contains(providerEventType)) {
            throw new IllegalArgumentException("Invalid provider reference receipt");
        }
    }
}
