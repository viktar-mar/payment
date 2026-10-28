package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.time.Instant;
import java.util.UUID;

/** Durable dispatch identity; contains no provider credentials or response payload. */
public record ProviderOperation(UUID id, PaymentAccountId accountId,
        String externalAccountId, String credentialsRef, Kind kind,
        UUID resourceId, String operationKey, String fingerprint,
        Status status, String providerReference, Instant firstDispatchedAt,
        Instant retryBefore, String leaseOwner, Instant leaseExpiresAt, long version) {
    public enum Kind { PRODUCT_CREATE, PRICE_CREATE, PAYMENT_PREPARE, REFUND_CREATE }
    public enum Status { RESERVED, DISPATCHING, ACKNOWLEDGED, UNKNOWN, FAILED }
}
