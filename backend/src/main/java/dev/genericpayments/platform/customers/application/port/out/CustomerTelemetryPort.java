package dev.genericpayments.platform.customers.application.port.out;

import java.time.Duration;

/** Customer telemetry accepts bounded vocabulary only, never identifiers or credentials. */
public interface CustomerTelemetryPort {
    enum Mode { SITE_TOKEN, OIDC_TOKEN, EMAIL_OTP }
    enum Outcome { SUCCESS, REJECTED, RATE_LIMITED, UNAVAILABLE, CONFLICT, ALREADY_LINKED }
    enum OtpOutcome { REQUESTED, VERIFIED, REJECTED, RATE_LIMITED, UNAVAILABLE }
    enum MailOutcome { DELIVERED, TRANSIENT, PERMANENT, CONFIGURATION, LEASE_LOST }
    enum SessionOperation { ISSUE, REVOKE, EXPIRE }
    CustomerTelemetryPort NOOP=new CustomerTelemetryPort() {};
    default void authExchange(Mode mode,Outcome outcome,Duration duration) {}
    default void otp(OtpOutcome outcome) {}
    default void mail(MailOutcome outcome) {}
    default void session(SessionOperation operation) {}
    default void link(Outcome outcome) {}
    default void anonymization(Outcome outcome) {}
}
