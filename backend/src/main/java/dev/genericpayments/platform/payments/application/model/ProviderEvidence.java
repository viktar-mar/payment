package dev.genericpayments.platform.payments.application.model;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.paymentaccounts.domain.*;

public record ProviderEvidence(PaymentProviderType provider, PaymentAccountId accountId,
        PaymentAccountMode mode, String externalAccountId, Money money,
        String paymentReference, String chargeReference, String failedChargeReference, boolean captured) {}
