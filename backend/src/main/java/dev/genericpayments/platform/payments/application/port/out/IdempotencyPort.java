package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import java.time.Instant;

public interface IdempotencyPort {
    record Claim(boolean owner, IdempotencyRecord record) {}
    /** Atomically insert or lock/reload; matching expired replay material is destroyed under that lock. */
    Claim claim(IdempotencyRecord candidate, Instant now);
    /** Must join the caller's business transaction when present. */
    IdempotencyRecord complete(Ownership owner, Completion completion, Instant now);
    /** Handles an expiry crossed while waiting for claim/replay locks; never inserts a replacement. */
    void expireSecret(java.util.UUID recordId, Instant now);
    /** Shred expired material, then delete only completed records whose retention elapsed. */
    int cleanup(Instant now);
}
