package dev.genericpayments.platform.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.catalog.domain.BillingInterval;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.PriceKey;
import dev.genericpayments.platform.catalog.domain.PriceType;
import dev.genericpayments.platform.catalog.domain.Product;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.catalog.domain.ProductKey;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.paymentaccounts.adapter.out.persistence.PaymentAccountPersistenceAdapter;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.sites.adapter.out.persistence.SitePersistenceAdapter;
import dev.genericpayments.platform.sites.domain.AuthenticationMode;
import dev.genericpayments.platform.sites.domain.PaymentMethodPolicy;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class PublishedPriceConstraintTest extends PostgresIntegrationTest {
    @Autowired
    private PaymentAccountPersistenceAdapter accounts;

    @Autowired
    private SitePersistenceAdapter sites;

    @Autowired
    private ProductPersistenceAdapter products;

    @Autowired
    private PricePersistenceAdapter prices;

    @BeforeEach
    void prepareDatabase() {
        clearDatabase();
    }

    @Test
    void allowsOnlyOnePublishedPriceForSiteAndPriceKey() {
        SiteId siteId = activeSite().id();
        ProductId productId = products.save(Product.create(new ProductId(UUID.randomUUID()), siteId,
            ProductKey.of("pro-plan"), "Pro plan", null, INITIAL_TIME)).id();
        prices.save(publishedPrice(siteId, productId, 1));

        assertThatThrownBy(() -> prices.save(publishedPrice(siteId, productId, 2)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Site activeSite() {
        PaymentAccount account = accounts.save(PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),
            "fake-account", PaymentProviderType.FAKE, PaymentAccountMode.SANDBOX, "fake-external", "vault://fake"));
        return sites.save(Site.create(new SiteId(UUID.randomUUID()), SiteKey.of("catalog-site"), "Catalog",
            account.id(), Set.of(URI.create("https://catalog.example.com")), Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.automatic()));
    }

    private static Price publishedPrice(SiteId siteId, ProductId productId, int version) {
        Instant publishedAt = INITIAL_TIME.plusSeconds(version);
        return new Price(new PriceId(UUID.randomUUID()), siteId, productId, PriceKey.of("pro-monthly"), version,
            PriceType.SUBSCRIPTION, Money.of(1999, "EUR"), BillingInterval.MONTH, 0, TaxMode.NONE, null,
            dev.genericpayments.platform.catalog.domain.PriceStatus.PUBLISHED, publishedAt, INITIAL_TIME, publishedAt);
    }
}
