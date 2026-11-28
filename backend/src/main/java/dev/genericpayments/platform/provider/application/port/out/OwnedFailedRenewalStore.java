package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Caller-owned lifecycle transaction; never performs provider work. */
public interface OwnedFailedRenewalStore {
    Admission admitInTransaction(OwnedFirstRenewal.Admission upstream, OwnedFirstRenewal.LaterReady ready,
            OwnedFirstRenewal.Boundary boundary, Instant utcAt);
    Admission requireCurrentInTransaction(Admission admission);
    default void lockGraceLastSecondInTransaction(Admission admission) { throw new IllegalStateException("Owned grace preparation unsupported"); }
    default GraceLastSecondPreparation prepareGraceLastSecondInTransaction(Admission admission,Failure failure,Instant utcAt) {
        throw new IllegalStateException("Owned grace preparation unsupported");
    }
    default GraceLastSecondPreparation requireGraceLastSecondInTransaction(Admission admission,GraceLastSecondPreparation preparation) {
        throw new IllegalStateException("Owned grace preparation unsupported");
    }
    ReadClaim claimReadInTransaction(Admission admission, Phase phase, UUID invocation,
            ApplySubscriptionSnapshotUseCase.Fence fence, Instant utcAt);
    ReadClaim requireReadInTransaction(Admission admission, ReadClaim claim);
    Failure recordFailureInTransaction(Admission admission, ReadClaim claim, FailedEvidence evidence);
    Failure requireFailureInTransaction(Admission admission);
    Recovery associateInTransaction(Admission admission, SubscriptionOperation operation,
            String allocation, String paymentIntent, Instant utcAt);
    Recovery requireRecoveryInTransaction(Admission admission);
    Publication recordPublicationInTransaction(Admission admission, ReadClaim claim, Instant utcAt);
    Optional<Publication> publicationInTransaction(Admission admission);
    void stopInTransaction(Admission admission, Reason reason, Instant utcAt);
    void requireRecoveryFactInTransaction(ApplySubscriptionSnapshotUseCase.Command command,
            Subscription lockedSubscription, List<SubscriptionOperation> lockedOperations, Instant billingAt, Instant utcAt);
}
