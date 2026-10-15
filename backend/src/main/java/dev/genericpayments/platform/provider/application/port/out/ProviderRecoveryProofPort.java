package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.provider.application.model.*;

public interface ProviderRecoveryProofPort {
    RecoveredProviderProof verify(
            ProviderOperation operation, String providerReference, String creationEventId);

    ResolvedProviderRecovery retrieve(ProviderOperation operation);

    RecoveryRequestSnapshot request(ProviderOperation operation);

    void replay(ProviderOperation operation);
}
