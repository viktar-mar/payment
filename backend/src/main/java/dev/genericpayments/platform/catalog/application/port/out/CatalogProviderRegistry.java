package dev.genericpayments.platform.catalog.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;

public interface CatalogProviderRegistry {
    CatalogProviderPort select(PaymentAccount account);
}
