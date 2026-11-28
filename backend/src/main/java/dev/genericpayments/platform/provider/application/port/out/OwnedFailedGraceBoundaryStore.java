package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceBoundary.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.PreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Admission;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.GraceLastSecondPreparation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import java.time.Instant;

/** Dedicated writer on the verified caller connection. No nested transaction or ordinary fallback. */
public interface OwnedFailedGraceBoundaryStore {
    /** Verify the caller and bound all following authority/proof SQL before any descriptive lock. */
    default void beforeAuthorityInTransaction(InvocationBudget budget){throw denied();}
    default void beforeFreshEntryInTransaction(Admission admission,GraceLastSecondPreparation preparation,Phase phase,InvocationBudget budget){throw denied();}
    default BoundaryContext requirePredecessorInTransaction(PreparationContext original,Phase phase,InvocationBudget budget){throw denied();}
    default ProviderOperation firstClaimInTransaction(BoundaryContext c,String claimant,Instant now,InvocationBudget budget){throw denied();}
    default ProviderOperation requireClaimInTransaction(BoundaryContext c,ProviderOperation claim,Instant now,InvocationBudget budget){throw denied();}
    default BoundaryAck acknowledgeInTransaction(BoundaryContext c,ProviderOperation claim,TestClockAck observation,Instant observedAt,Instant now,InvocationBudget budget){throw denied();}
    default BoundaryAck requireAcknowledgedInTransaction(BoundaryContext c,BoundaryAck ack,Instant now,InvocationBudget budget){throw denied();}
    default BoundaryReady recordReadyInTransaction(BoundaryContext c,BoundaryAck ack,TestClockReady observation,Instant observedAt,Instant now,InvocationBudget budget){throw denied();}
    default void uncertainInTransaction(BoundaryContext c,ProviderOperation claim,BoundaryAck ack,InvocationBudget budget,Instant now){throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("OWNED_FAILED_GRACE_BOUNDARY_UNAVAILABLE");}
}

