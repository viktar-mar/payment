package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
/** Only Task7's original setup continuation; checked after IO and before each guarded write. */
public interface SubscriptionSetupContinuationGuard {
    void requireCurrent(Command command,SetupContinuationFence fence);
}
