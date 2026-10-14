package dev.genericpayments.platform.paymentaccounts.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.util.Optional;
public interface LockPaymentAccountPort { Optional<PaymentAccount> findByIdForUpdate(PaymentAccountId id); }
