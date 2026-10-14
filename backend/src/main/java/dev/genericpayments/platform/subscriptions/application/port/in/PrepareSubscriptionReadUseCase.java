package dev.genericpayments.platform.subscriptions.application.port.in;

import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;

/** Owned preflight for external observations; callers still require locked apply revalidation. */
public interface PrepareSubscriptionReadUseCase {
    SubscriptionOperation financial(Subscription subscription);
}
