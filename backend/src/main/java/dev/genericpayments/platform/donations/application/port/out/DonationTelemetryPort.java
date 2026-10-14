package dev.genericpayments.platform.donations.application.port.out;

import java.time.Duration;

/** Only finite enums cross this boundary; identifying context and message content are never labels. */
public interface DonationTelemetryPort {
    enum CheckoutOutcome { CREATED, REPLAYED, REJECTED_AMOUNT, REJECTED_ANONYMOUS, REJECTED_SESSION, CONFLICT, REJECTED, FAILED }
    enum RedactionOutcome { REDACTED, REPLAYED, UNCHANGED, CONFLICT, REJECTED, FAILED }
    void checkout(CheckoutOutcome outcome, Duration duration);
    void redaction(RedactionOutcome outcome);
}
