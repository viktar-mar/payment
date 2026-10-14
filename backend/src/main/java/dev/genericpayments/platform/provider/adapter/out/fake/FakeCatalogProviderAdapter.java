package dev.genericpayments.platform.provider.adapter.out.fake;

import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
import dev.genericpayments.platform.catalog.application.port.out.CatalogProviderPort;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.Product;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;

public final class FakeCatalogProviderAdapter implements CatalogProviderPort {
    @Override
    public ProviderProductRef ensureProduct(PaymentAccount account, Product product) {
        return new ProviderProductRef("fake_prod_" + product.id().value());
    }

    @Override
    public ProviderPriceRef createPrice(PaymentAccount account, Product product, Price price) {
        return new ProviderPriceRef("fake_price_" + price.id().value() + "_v" + price.version());
    }
}
