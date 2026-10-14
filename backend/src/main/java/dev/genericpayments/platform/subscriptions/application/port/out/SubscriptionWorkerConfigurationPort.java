package dev.genericpayments.platform.subscriptions.application.port.out;

import java.time.Duration;

public interface SubscriptionWorkerConfigurationPort {
    int batchSize();
    Duration lease();
    Duration delay();
    Duration backoff(int attempts);
}
