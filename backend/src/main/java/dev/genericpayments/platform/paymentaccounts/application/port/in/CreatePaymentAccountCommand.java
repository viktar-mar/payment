package dev.genericpayments.platform.paymentaccounts.application.port.in;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;

public record CreatePaymentAccountCommand(String key, PaymentProviderType provider,
                                          PaymentAccountMode mode, String externalAccountId,
                                          String credentialsRef) {
}
