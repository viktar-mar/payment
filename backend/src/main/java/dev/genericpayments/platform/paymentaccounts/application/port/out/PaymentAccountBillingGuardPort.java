package dev.genericpayments.platform.paymentaccounts.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
public interface PaymentAccountBillingGuardPort {void requireMutable(PaymentAccountId id);}
