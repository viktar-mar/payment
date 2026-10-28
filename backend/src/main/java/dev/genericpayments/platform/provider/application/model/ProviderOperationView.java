package dev.genericpayments.platform.provider.application.model;

import java.time.Instant;
import java.util.UUID;

/** Allowlisted operator facts. Never a dispatch claim or financial authority. */
public record ProviderOperationView(UUID operationId, UUID paymentId, UUID accountId, String accountMode,
        String kind, String status, String reference, Instant firstDispatchedAt,
        Instant retryBefore, Instant nextAttemptAt, String safeErrorCode,
        UUID resourceId, UUID siteId, boolean manualReviewRequired) {}
