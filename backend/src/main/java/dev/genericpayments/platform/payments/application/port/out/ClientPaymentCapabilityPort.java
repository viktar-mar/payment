package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.ClientPaymentCapability;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.Payment;
public interface ClientPaymentCapabilityPort {
    ClientPaymentCapability retrieve(PaymentAccount account, Payment payment);
}
