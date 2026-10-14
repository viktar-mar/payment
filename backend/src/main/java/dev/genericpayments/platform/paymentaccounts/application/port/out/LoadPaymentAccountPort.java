package dev.genericpayments.platform.paymentaccounts.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.util.Optional;

public interface LoadPaymentAccountPort {
    Optional<PaymentAccount> findById(PaymentAccountId id);

    Optional<PaymentAccount> findByKey(String key);
}
