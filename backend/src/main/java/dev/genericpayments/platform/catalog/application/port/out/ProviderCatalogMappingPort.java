package dev.genericpayments.platform.catalog.application.port.out;

import dev.genericpayments.platform.catalog.application.model.ProviderCatalogMapping;
import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.util.Optional;

public interface ProviderCatalogMappingPort {
    Optional<ProviderProductRef> findProduct(PaymentAccountId accountId, ProductId productId);
    Optional<ProviderPriceRef> findPrice(PaymentAccountId accountId, PriceId priceId);
    ProviderCatalogMapping save(ProviderCatalogMapping mapping);
}
