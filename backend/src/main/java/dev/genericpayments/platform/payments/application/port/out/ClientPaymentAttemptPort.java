package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentId;
import java.util.Optional;
import java.util.UUID;

/** Immutable baseline of a browser attempt; a Charge is not a one-to-one UI attempt. */
public interface ClientPaymentAttemptPort {
    record Baseline(UUID attemptId, PaymentAccountId accountId, PaymentId paymentId, UUID customerId,
            String credentialsRef, String externalAccountId, String initialFailedChargeReference) {}
    boolean record(Baseline baseline);
    Optional<Baseline> findByAttempt(UUID attemptId);
}
