package dev.genericpayments.platform.subscriptions.application.port.out;

import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.List;

/** Explicit persisted authority for the original zero-amount optional trial publication. */
public interface SubscriptionIsolatedTrialProofPort {
    void requireAuthorized(Command command,Subscription subscription,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt);
    static SubscriptionIsolatedTrialProofPort denied() {
        return (command,subscription,operations,billingAt,utcAt)->{throw new IllegalStateException("ISOLATED_BILLING_UNSUPPORTED");};
    }
}
