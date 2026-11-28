package dev.genericpayments.platform.provider.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Original synchronous response only; created is opaque, while the service interval contains READY F. */
public record OwnedClockSubscriptionReceipt(OwnedClockSubscription intent, UUID providerOperation, String reference,
        long providerCreated, String itemReference, long periodStart, long periodEnd, Instant acknowledgedAt) {
    public OwnedClockSubscriptionReceipt {
        Objects.requireNonNull(intent); Objects.requireNonNull(providerOperation); Objects.requireNonNull(acknowledgedAt);
        if(reference==null || !reference.matches("sub_[A-Za-z0-9]{1,251}") || providerCreated<=0
            || itemReference==null || !itemReference.matches("si_[A-Za-z0-9]{1,252}")
            || periodStart<=0 || periodStart>intent.clock().frozenTime() || periodEnd<=intent.clock().frozenTime()
            || acknowledgedAt.isBefore(intent.admittedAt())) throw new IllegalArgumentException("Invalid owned Subscription receipt");
    }
}
