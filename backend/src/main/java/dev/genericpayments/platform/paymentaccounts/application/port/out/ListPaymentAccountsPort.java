package dev.genericpayments.platform.paymentaccounts.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import java.util.List;
public interface ListPaymentAccountsPort { List<PaymentAccount> findAll(); }
