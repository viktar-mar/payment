package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.RecoveryPreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import java.time.Instant;
import java.util.Optional;

/** Dedicated caller-transaction receipt boundary. No nested transactions or provider work. */
public interface OwnedRecoveryConfirmStore {
    default Optional<ProviderOperation> lockInTransaction(RecoveryPreparationContext context){throw denied();}
    default ProviderOperation insertAndClaimInTransaction(RecoveryPreparationContext context,String claimant,Instant now,InvocationBudget budget){throw denied();}
    default ProviderOperation requireClaimInTransaction(RecoveryPreparationContext context,ProviderOperation claim,InvocationBudget budget,Instant now){throw denied();}
    default ConfirmationAck acknowledgeInTransaction(RecoveryPreparationContext context,ProviderOperation claim,ConfirmReply reply,Instant now,InvocationBudget budget){throw denied();}
    /** True only if this exact immutable original relation consumed its own committed first claim. */
    default boolean unknownInTransaction(RecoveryPreparationContext context,ProviderOperation claim){throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("OWNED_RECOVERY_CONFIRM_UNAVAILABLE");}
}
