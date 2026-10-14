package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.domain.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "payment.telemetry.site-keys[0]=test-site")
public abstract class CheckoutTestFixture extends ProviderTestFixture {
    @Autowired protected SaveProductPort products;
    @Autowired protected SavePricePort prices;
    protected ProductId product;
    protected Price price;
    @BeforeEach void catalog() {
        product = new ProductId(UUID.randomUUID());
        products.save(Product.create(product, site, ProductKey.of("lifetime"), "Lifetime", null, INITIAL_TIME));
        price = catalogPrice("lifetime", 1, PriceType.ONE_TIME, TaxMode.NONE, true);
    }
    protected Price catalogPrice(String key, int version, PriceType type, TaxMode tax, boolean published) {
        var p = Price.draft(new PriceId(UUID.randomUUID()), site, product, PriceKey.of(key), version,
            type, Money.of(4999, "EUR"), type == PriceType.SUBSCRIPTION ? BillingInterval.MONTH : null,
            0, tax, null, INITIAL_TIME);
        return prices.save(published ? p.publish(INITIAL_TIME) : p);
    }
}
