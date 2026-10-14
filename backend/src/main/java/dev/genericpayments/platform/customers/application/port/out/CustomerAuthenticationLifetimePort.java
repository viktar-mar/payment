package dev.genericpayments.platform.customers.application.port.out;

import java.time.Duration;

public interface CustomerAuthenticationLifetimePort {
    Duration oidcAttemptTtl();
    Duration linkProofTtl();
}
