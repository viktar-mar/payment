package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.Payment;
import java.util.Optional;

public interface SubscriptionCashIdentityPort {
    /** Serializes even absent identities; caller retains the transaction through insert and fact application. */
    Optional<Payment> lock(PaymentAccountId accountId,String providerReference);
}
