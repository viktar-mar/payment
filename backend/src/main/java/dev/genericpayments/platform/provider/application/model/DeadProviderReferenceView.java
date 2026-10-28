package dev.genericpayments.platform.provider.application.model;

import java.time.Instant;
import java.util.UUID;

/** A dead reference receipt can be foreign/unmapped and have no local Payment. */
public record DeadProviderReferenceView(UUID receiptId,UUID accountId,String accountMode,String eventId,
        String objectId,String objectType,Instant occurredAt,Instant receivedAt,
        int attemptCount,String safeErrorCode,String status) {}
