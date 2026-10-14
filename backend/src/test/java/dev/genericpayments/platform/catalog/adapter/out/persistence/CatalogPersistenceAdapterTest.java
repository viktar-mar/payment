package dev.genericpayments.platform.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.genericpayments.platform.catalog.application.model.ProviderCatalogMapping;
import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class CatalogPersistenceAdapterTest extends PostgresIntegrationTest {
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
    private TransactionTemplate transactions;

    private ExecutorService executor;

    @BeforeEach
    void prepareDatabase() {
        clearDatabase();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void roundTripsProductsAndPricesWithClockTimestamps() {
        SiteId siteId = activeSite().id();
        Product originalProduct = product(siteId, "pro-plan");
        Product savedProduct = products.save(originalProduct);
        Price originalPrice = draftPrice(siteId, savedProduct.id(), "pro-monthly", 1);

        Price savedPrice = prices.save(originalPrice);

        assertThat(savedProduct).isEqualTo(originalProduct);
        assertThat(products.findById(originalProduct.id())).contains(originalProduct);
        assertThat(products.findBySiteId(siteId)).containsExactly(originalProduct);
        assertThat(savedPrice).isEqualTo(originalPrice);
        assertThat(prices.findById(originalPrice.id())).contains(originalPrice);
        assertThat(prices.findBySiteId(siteId)).containsExactly(originalPrice);
    }

    @Test
    void allocatesVersionsWithinSiteAndPriceKey() {
        SiteId siteId = activeSite().id();
        ProductId productId = products.save(product(siteId, "pro-plan")).id();

        assertThat(prices.nextVersion(siteId, PriceKey.of("pro-monthly"))).isEqualTo(1);
        prices.save(draftPrice(siteId, productId, "pro-monthly", 1));

        assertThat(prices.nextVersion(siteId, PriceKey.of("pro-monthly"))).isEqualTo(2);
    }

    @Test
    void allocatesDistinctVersionsForConcurrentFirstRequests() throws Exception {
        SiteId siteId = activeSite().id();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<CompletableFuture<Integer>> allocations = List.of(
            allocateWhenStarted(siteId, ready, start),
            allocateWhenStarted(siteId, ready, start));
        ready.await();
        start.countDown();

        assertThat(allocations.stream().map(CompletableFuture::join).toList()).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void rollsBackVersionAllocationWithThePriceInsert() {
        SiteId siteId = activeSite().id();
        ProductId productId = products.save(product(siteId, "pro-plan")).id();
        Price rolledBack = draftPrice(siteId, productId, "pro-monthly", 1);

        transactions.executeWithoutResult(status -> {
            assertThat(prices.nextVersion(siteId, PriceKey.of("pro-monthly"))).isEqualTo(1);
            prices.save(rolledBack);
            status.setRollbackOnly();
        });

        assertThat(prices.findById(rolledBack.id())).isEmpty();
        assertThat(prices.nextVersion(siteId, PriceKey.of("pro-monthly"))).isEqualTo(1);
    }

    @Test
    void mappingIsScopedByPaymentAccount() {
        Site site = activeSite();
        PaymentAccount accountA = accounts.findById(site.paymentAccountId()).orElseThrow();
        PaymentAccount accountB = accounts.save(account("fake-account-b"));
        Product product = products.save(product(site.id(), "pro-plan"));
        Price price = prices.save(draftPrice(site.id(), product.id(), "pro-monthly", 1));
        ProviderCatalogMapping mapping = new ProviderCatalogMapping(accountA.id(), product.id(),
            new ProviderProductRef("fake_prod_a"), price.id(), new ProviderPriceRef("fake_price_a"), INITIAL_TIME);

        mappings.save(mapping);

        assertThat(mappings.findProduct(accountA.id(), product.id())).contains(new ProviderProductRef("fake_prod_a"));
        assertThat(mappings.findPrice(accountA.id(), price.id())).contains(new ProviderPriceRef("fake_price_a"));
        assertThat(mappings.findPrice(accountB.id(), price.id())).isEmpty();
    }

    private CompletableFuture<Integer> allocateWhenStarted(SiteId siteId, CountDownLatch ready, CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                start.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return prices.nextVersion(siteId, PriceKey.of("pro-monthly"));
        }, executor);
    }

    private Site activeSite() {
        PaymentAccount account = accounts.save(account("fake-account-a"));
        return sites.save(Site.create(new SiteId(UUID.randomUUID()), SiteKey.of("catalog-site"), "Catalog",
            account.id(), Set.of(URI.create("https://catalog.example.com")), Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.automatic()));
    }

    private static PaymentAccount account(String key) {
        return PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), key, PaymentProviderType.FAKE,
            PaymentAccountMode.SANDBOX, key + "-external", "vault://" + key);
    }

    private static Product product(SiteId siteId, String key) {
        return Product.create(new ProductId(UUID.randomUUID()), siteId, ProductKey.of(key), "Pro plan",
            "For professional customers", INITIAL_TIME);
    }

    private static Price draftPrice(SiteId siteId, ProductId productId, String key, int version) {
        return Price.draft(new PriceId(UUID.randomUUID()), siteId, productId, PriceKey.of(key), version,
            PriceType.SUBSCRIPTION, Money.of(1999, "EUR"), BillingInterval.MONTH, 14, TaxMode.INCLUSIVE,
            "txcd_10000000", INITIAL_TIME);
    }
}
