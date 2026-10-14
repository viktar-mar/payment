package dev.genericpayments.platform.catalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.catalog.adapter.out.persistence.PricePersistenceAdapter;
import dev.genericpayments.platform.catalog.adapter.out.persistence.ProductPersistenceAdapter;
import dev.genericpayments.platform.catalog.adapter.out.persistence.ProviderCatalogMappingPersistenceAdapter;
import dev.genericpayments.platform.catalog.application.port.in.CreatePriceCommand;
import dev.genericpayments.platform.catalog.domain.BillingInterval;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.PriceKey;
import dev.genericpayments.platform.catalog.domain.PriceStatus;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class CatalogTransactionalServiceIntegrationTest extends PostgresIntegrationTest {
    @Autowired
    private PaymentAccountPersistenceAdapter accounts;

    @Autowired
    private SitePersistenceAdapter sites;

    @Autowired
    private ProductPersistenceAdapter products;

    @Autowired
    private PricePersistenceAdapter prices;

    @Autowired
    private ProviderCatalogMappingPersistenceAdapter mappings;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CreatePriceService createPrices;

    @Autowired
    private PublishPriceService publishPrices;

    @BeforeEach
    void prepareDatabase() {
        clearDatabase();
    }

    @Test
    void createPriceIsProxiedAndRollsBackVersionWhenThePriceInsertFails() {
        Product product = product(activeSite().id());
        products.save(product);
        prices.save(draft(product, 1));

        assertThat(AopUtils.isAopProxy(createPrices)).isTrue();
        assertThatThrownBy(() -> createPrices.create(command(product)))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(counter(product.siteId(), "pro-monthly")).isZero();
    }

    @Test
    void publishPriceIsProxiedAndRollsBackProviderMappingsWhenPublishingFails() {
        Product product = product(activeSite().id());
        products.save(product);
        Price draft = prices.save(draft(product, 1));
        prices.save(draft(product, 2).publish(INITIAL_TIME.plusSeconds(1)));

        assertThat(AopUtils.isAopProxy(publishPrices)).isTrue();
        assertThatThrownBy(() -> publishPrices.publish(draft.id()))
            .isInstanceOf(DataIntegrityViolationException.class);

        PaymentAccountId accountId = sites.findById(product.siteId()).orElseThrow().paymentAccountId();
        assertThat(mappings.findProduct(accountId, product.id())).isEmpty();
        assertThat(mappings.findPrice(accountId, draft.id())).isEmpty();
        assertThat(prices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
    }

    private long counter(SiteId siteId, String key) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from price_version_counters where site_id = ? and price_key = ?", Long.class,
            siteId.value(), key);
        return count == null ? 0 : count;
    }

    private Site activeSite() {
        PaymentAccount account = accounts.save(PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),
            "fake-account", PaymentProviderType.FAKE, PaymentAccountMode.SANDBOX, "fake-external", "vault://fake"));
        return sites.save(Site.create(new SiteId(UUID.randomUUID()), SiteKey.of("catalog-site"), "Catalog",
            account.id(), Set.of(URI.create("https://catalog.example.com")), Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.automatic()));
    }

    private static Product product(SiteId siteId) {
        return Product.create(new ProductId(UUID.randomUUID()), siteId, ProductKey.of("pro-plan"), "Pro plan", null,
            INITIAL_TIME);
    }

    private static CreatePriceCommand command(Product product) {
        return new CreatePriceCommand(product.siteId(), product.id(), "pro-monthly", PriceType.SUBSCRIPTION,
            Money.of(1999, "EUR"), BillingInterval.MONTH, 0, TaxMode.NONE, null);
    }

    private static Price draft(Product product, int version) {
        return Price.draft(new PriceId(UUID.randomUUID()), product.siteId(), product.id(), PriceKey.of("pro-monthly"),
            version, PriceType.SUBSCRIPTION, Money.of(1999, "EUR"), BillingInterval.MONTH, 0, TaxMode.NONE, null,
            INITIAL_TIME);
    }
}
