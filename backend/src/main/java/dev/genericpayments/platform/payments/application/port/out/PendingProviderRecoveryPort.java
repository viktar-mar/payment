package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Due-work ownership only. Never grants provider dispatch or financial authority. */
public interface PendingProviderRecoveryPort {
    record Claim(ProviderOperation operation, int attempt, Instant expiresAt) {}

    enum Outcome {
        RETRY_PENDING,
        REPLAY_ACKNOWLEDGED,
        COMPLETE,
        FAILED,
        MANUAL_REVIEW,
        CATALOG_PUBLICATION_REQUIRED
    }

    Optional<Claim> claimNext();

    Optional<Claim> claim(UUID operationId);

    void finish(Claim claim, Outcome outcome);
    /** Clear resolved visibility only; never reset failed history or change financial facts. */
    void clearResolved(ProviderOperation expected);
}
