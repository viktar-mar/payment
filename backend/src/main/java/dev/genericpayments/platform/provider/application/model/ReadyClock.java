package dev.genericpayments.platform.provider.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact original ACK plus immutable provider evidence; provider times have no inferred UTC relationship. */
public record ReadyClock(ClockOwner owner, UUID creationOperation, String reference,
        long frozenTime, long providerCreated, long providerDeletesAfter, Instant observedAt,
        String customerFingerprint) {
    public ReadyClock {
        Objects.requireNonNull(owner); Objects.requireNonNull(creationOperation); Objects.requireNonNull(observedAt);
        if (reference==null || !reference.matches("clock_[A-Za-z0-9]{1,249}")
            || frozenTime!=owner.initialFrozenTime() || providerCreated<=0 || providerDeletesAfter<=0
            || customerFingerprint==null || !customerFingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid READY clock evidence");
    }
}
