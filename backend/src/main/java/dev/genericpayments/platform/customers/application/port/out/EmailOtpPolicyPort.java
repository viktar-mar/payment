package dev.genericpayments.platform.customers.application.port.out;

import java.time.Duration;

public interface EmailOtpPolicyPort {
    Duration ttl();
    int maxAttempts();
    Duration resendCooldown();
    int siteRequestLimit();
    int emailRequestLimit();
    int sourceAddressRequestLimit();
    Duration rateWindow();
    int mailBatchSize();
    Duration mailLease();
    int mailMaxAttempts();
    Duration mailMaximumBackoff();
    Duration mailTerminalRetention();
    int mailCleanupBatch();
    Duration challengeRetention();
    int challengeCleanupBatch();
    boolean mailWorkerEnabled();
    Duration mailWorkerDelay();
    int rateCleanupBatch();
}
