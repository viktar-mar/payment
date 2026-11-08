package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Admission;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.GraceLastSecondPreparation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import java.time.Instant;

/** Dedicated writer on the verified caller connection. No nested transaction or ordinary fallback. */
public interface OwnedFailedGraceAdvanceStore {
    /** Verify the caller and bound all following authority/proof SQL before any descriptive lock. */
    default void beforeAuthorityInTransaction(InvocationBudget budget){throw denied();}
    default void beforeFreshEntryInTransaction(Admission admission,GraceLastSecondPreparation preparation,InvocationBudget budget){throw denied();}
    default ProviderOperation firstClaimInTransaction(PreparationContext c,String claimant,Instant now,InvocationBudget budget){throw denied();}
    default ProviderOperation requireClaimInTransaction(PreparationContext c,ProviderOperation claim,Instant now,InvocationBudget budget){throw denied();}
    default AdvanceAck acknowledgeInTransaction(PreparationContext c,ProviderOperation claim,TestClockAck observation,Instant observedAt,Instant now,InvocationBudget budget){throw denied();}
    default AdvanceAck requireAcknowledgedInTransaction(PreparationContext c,AdvanceAck ack,Instant now,InvocationBudget budget){throw denied();}
    default GraceAdvanceReady recordReadyInTransaction(PreparationContext c,AdvanceAck ack,TestClockReady observation,Instant observedAt,Instant now,InvocationBudget budget){throw denied();}
    default void uncertainInTransaction(PreparationContext c,ProviderOperation claim,AdvanceAck ack,InvocationBudget budget,Instant now){throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("OWNED_FAILED_GRACE_ADVANCE_UNAVAILABLE");}
}
