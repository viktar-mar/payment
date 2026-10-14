package dev.genericpayments.platform.catalog.application.port.out;

import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.Product;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;

public interface CatalogProviderPort {
    ProviderProductRef ensureProduct(PaymentAccount account, Product product);
    ProviderPriceRef createPrice(PaymentAccount account, Product product, Price price);
}
