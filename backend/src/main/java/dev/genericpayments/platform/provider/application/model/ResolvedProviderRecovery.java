package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.application.model.ProviderState;
import dev.genericpayments.platform.payments.domain.Payment;

public record ResolvedProviderRecovery(
        ProviderOperation operation,
        PaymentAccount verifiedAccount,
        Payment verifiedPayment,
        ProviderState state) {}
