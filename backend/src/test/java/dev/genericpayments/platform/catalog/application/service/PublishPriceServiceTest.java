package dev.genericpayments.platform.catalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.catalog.application.model.ProviderCatalogMapping;
import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
import dev.genericpayments.platform.catalog.application.port.out.CatalogProviderPort;
import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.application.port.out.LoadProductPort;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.catalog.application.port.out.SavePricePort;
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
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeCatalogProviderAdapter;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.AuthenticationMode;
import dev.genericpayments.platform.sites.domain.PaymentMethodPolicy;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublishPriceServiceTest {
    @Test
    void savesProviderMappingBeforeSavingPublishedPrice() {
        PublicationFixture fixture = new PublicationFixture(false);

        Price published = fixture.service.publish(fixture.draft.id());

        assertThat(published.status()).isEqualTo(PriceStatus.PUBLISHED);
        assertThat(fixture.mappingPort.mapping.productRef().value()).startsWith("fake_prod_");
        assertThat(fixture.mappingPort.mapping.priceRef().value()).startsWith("fake_price_");
        assertThat(fixture.calls).containsSubsequence("provider.ensureProduct", "provider.createPrice", "mapping.save", "price.save");
    }

    @Test
    void providerFailureLeavesDraftWithoutDurableMappingOrPriceSave() {
        PublicationFixture fixture = new PublicationFixture(true);

        assertThatThrownBy(() -> fixture.service.publish(fixture.draft.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("provider unavailable");

        assertThat(fixture.calls).containsExactly(
            "price.load", "product.load", "site.load", "account.load", "mapping.findProduct",
            "provider.ensureProduct", "mapping.findPrice", "provider.createPrice");
        assertThat(fixture.mappingPort.mapping).isNull();
        assertThat(fixture.pricePort.saved).isNull();
        assertThat(fixture.pricePort.stored.status()).isEqualTo(PriceStatus.DRAFT);
    }

    private static final class PublicationFixture {
        private final List<String> calls = new ArrayList<>();
        private final Price draft;
        private final RecordingPricePort pricePort;
        private final RecordingMappingPort mappingPort;
        private final PublishPriceService service;

        private PublicationFixture(boolean providerFails) {
            SiteId siteId = new SiteId(UUID.randomUUID());
            PaymentAccountId accountId = new PaymentAccountId(UUID.randomUUID());
            Product product = Product.create(new ProductId(UUID.randomUUID()), siteId, ProductKey.of("pro-plan"),
                "Pro plan", null, Instant.parse("2026-09-12T10:00:00Z"));
            draft = Price.draft(new PriceId(UUID.randomUUID()), siteId, product.id(),
                PriceKey.of("pro-monthly"), 1, PriceType.SUBSCRIPTION, Money.of(1999, "EUR"),
                BillingInterval.MONTH, 14, TaxMode.INCLUSIVE, "txcd_10000000",
                Instant.parse("2026-09-12T10:00:00Z"));
            pricePort = new RecordingPricePort(draft, calls);
            mappingPort = new RecordingMappingPort(calls);
            var locks = new dev.genericpayments.platform.catalog.application.port.out.CatalogPublicationLockPort() {
                public Versions versions(PaymentAccountId a, SiteId s, ProductId p, PriceId r) { return new Versions(0, 0, 0, 0); }
                public Versions lock(PaymentAccountId a, SiteId s, ProductId p, PriceId r) { return versions(a, s, p, r); }
            };
            var phase = new PricePublicationTransaction(pricePort, pricePort, new RecordingProductPort(product, calls),
                new RecordingSitePort(site(siteId, accountId), calls),
                new RecordingAccountPort(account(accountId), calls),
                mappingPort, locks, (published, context) -> {},
                Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC));
            service = new PublishPriceService(phase, account -> new RecordingProvider(calls, providerFails), mappingPort);
        }
    }

    private static Site site(SiteId siteId, PaymentAccountId accountId) {
        return Site.create(siteId, SiteKey.of("demo-site"), "Demo site", accountId,
            Set.of(URI.create("https://demo.example.com")), Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.automatic());
    }

    private static PaymentAccount account(PaymentAccountId id) {
        return PaymentAccount.create(id, "fake-account", PaymentProviderType.FAKE,
            PaymentAccountMode.SANDBOX, "fake_123", "env:FAKE_KEY");
    }

    private static final class RecordingPricePort implements LoadPricePort, SavePricePort {
        private final List<String> calls;
        private Price stored;
        private Price saved;

        private RecordingPricePort(Price stored, List<String> calls) {
            this.stored = stored;
            this.calls = calls;
        }

        @Override public Optional<Price> findById(PriceId id) {
            calls.add("price.load");
            return Optional.ofNullable(stored);
        }
        @Override public List<Price> findBySiteId(SiteId siteId) { return List.of(stored); }
        @Override public Optional<Price> findCurrentlyPublished(SiteId siteId, PriceKey key) {
            return Optional.ofNullable(stored).filter(p -> p.siteId().equals(siteId) && p.key().equals(key) && p.status() == PriceStatus.PUBLISHED);
        }
        @Override public int nextVersion(SiteId siteId, PriceKey key) { return 1; }
        @Override public Price save(Price price) {
            calls.add("price.save");
            saved = price;
            stored = price;
            return price;
        }
    }

    private static final class RecordingProductPort implements LoadProductPort {
        private final Product product;
        private final List<String> calls;

        private RecordingProductPort(Product product, List<String> calls) {
            this.product = product;
            this.calls = calls;
        }

        @Override public Optional<Product> findById(ProductId id) {
            calls.add("product.load");
            return product.id().equals(id) ? Optional.of(product) : Optional.empty();
        }
        @Override public List<Product> findBySiteId(SiteId id) { return List.of(product); }
    }

    private static final class RecordingSitePort implements LoadSitePort {
        private final Site site;
        private final List<String> calls;

        private RecordingSitePort(Site site, List<String> calls) {
            this.site = site;
            this.calls = calls;
        }

        @Override public Optional<Site> findById(SiteId id) {
            calls.add("site.load");
            return site.id().equals(id) ? Optional.of(site) : Optional.empty();
        }
        @Override public Optional<Site> findByKey(SiteKey key) { return Optional.of(site); }
        @Override public boolean existsByKey(SiteKey key) { return true; }
        @Override public List<Site> findAll() { return List.of(site); }
    }

    private static final class RecordingAccountPort implements LoadPaymentAccountPort {
        private final PaymentAccount account;
        private final List<String> calls;

        private RecordingAccountPort(PaymentAccount account, List<String> calls) {
            this.account = account;
            this.calls = calls;
        }

        @Override public Optional<PaymentAccount> findById(PaymentAccountId id) {
            calls.add("account.load");
            return account.id().equals(id) ? Optional.of(account) : Optional.empty();
        }
        @Override public Optional<PaymentAccount> findByKey(String key) { return Optional.of(account); }
    }

    private static final class RecordingMappingPort implements ProviderCatalogMappingPort {
        private final List<String> calls;
        private ProviderCatalogMapping mapping;

        private RecordingMappingPort(List<String> calls) { this.calls = calls; }

        @Override public Optional<ProviderProductRef> findProduct(PaymentAccountId accountId, ProductId productId) {
            calls.add("mapping.findProduct");
            return mapping == null ? Optional.empty() : Optional.of(mapping.productRef());
        }
        @Override public Optional<ProviderPriceRef> findPrice(PaymentAccountId accountId, PriceId priceId) {
            calls.add("mapping.findPrice");
            return mapping == null ? Optional.empty() : Optional.of(mapping.priceRef());
        }
        @Override public ProviderCatalogMapping save(ProviderCatalogMapping value) {
            calls.add("mapping.save");
            mapping = value;
            return value;
        }
    }

    private static final class RecordingProvider implements CatalogProviderPort {
        private final List<String> calls;
        private final boolean failWhenCreatingPrice;
        private final FakeCatalogProviderAdapter delegate = new FakeCatalogProviderAdapter();

        private RecordingProvider(List<String> calls, boolean failWhenCreatingPrice) {
            this.calls = calls;
            this.failWhenCreatingPrice = failWhenCreatingPrice;
        }

        @Override public ProviderProductRef ensureProduct(PaymentAccount account, Product product) {
            calls.add("provider.ensureProduct");
            return delegate.ensureProduct(account, product);
        }
        @Override public ProviderPriceRef createPrice(PaymentAccount account, Product product, Price price) {
            calls.add("provider.createPrice");
            if (failWhenCreatingPrice) {
                throw new IllegalStateException("provider unavailable");
            }
            return delegate.createPrice(account, product, price);
        }
    }
}
