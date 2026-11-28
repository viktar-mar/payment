package dev.genericpayments.platform.subscriptions.application.port.out;

import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.List;

/** Explicit persisted authority for completing the single owned isolated SETUP. */
public interface SubscriptionIsolatedMethodProofPort {
    void requireAuthorized(Command command,Subscription subscription,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt);
    static SubscriptionIsolatedMethodProofPort denied() {
        return (command,subscription,operations,billingAt,utcAt)->{throw new IllegalStateException("ISOLATED_BILLING_UNSUPPORTED");};
    }
}
