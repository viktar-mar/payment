package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentId;

public interface ProviderChargeReceiptPort {
    boolean record(PaymentAccountId account, PaymentId payment, String chargeId, boolean paid, String safeOutcome);
    boolean contains(PaymentAccountId account, String chargeId);
}
