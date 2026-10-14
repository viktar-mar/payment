package dev.genericpayments.platform.paymentaccounts.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;

public interface SavePaymentAccountPort {
    PaymentAccount save(PaymentAccount paymentAccount);
}
