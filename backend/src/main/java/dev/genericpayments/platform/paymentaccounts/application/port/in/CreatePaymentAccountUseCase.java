package dev.genericpayments.platform.paymentaccounts.application.port.in;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;

public interface CreatePaymentAccountUseCase {
    PaymentAccount create(CreatePaymentAccountCommand command);
}
