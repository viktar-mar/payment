package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.PrepareSubscriptionReadUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import java.time.Clock;

/** Explicit internal publication consumer; deliberately absent from ordinary registration. */
public final class OwnedFirstRenewalPublicationService {
    public OwnedFirstRenewalPublicationService(OwnedFirstRenewalStore store,SubscriptionTransactionPort transaction,
            SubscriptionOperationStore operations,SubscriptionLifecycleLockPort lifecycle,LockPaymentAccountPort accounts,
            SubscriptionStore subscriptions,PrepareSubscriptionReadUseCase reads,SubscriptionObservationPort observations,
            ApplySubscriptionSnapshotUseCase facts,Clock clock) {}

    public Publication publish(Admission admission,LaterReady ready,Paid paid) {
        throw new IllegalStateException("OWNED_FIRST_RENEWAL_PUBLICATION_UNAVAILABLE");
    }

    public Publication replayPublished(Admission admission) {
        throw new IllegalStateException("OWNED_FIRST_RENEWAL_PUBLICATION_UNAVAILABLE");
    }
}
