package dev.genericpayments.platform.catalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.catalog.application.port.in.CreatePriceCommand;
import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.application.port.out.LoadProductPort;
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
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreatePriceServiceTest {
    private final SiteId siteId = new SiteId(UUID.randomUUID());
    private final Product product = Product.create(new ProductId(UUID.randomUUID()), siteId,
        ProductKey.of("pro-plan"), "Pro plan", null, Instant.parse("2026-09-12T10:00:00Z"));

    @Test
    void rejectsMismatchedSiteBeforeAllocatingVersionOrSavingPrice() {
        InMemoryPricePort pricePort = new InMemoryPricePort(3);
        CreatePriceService service = new CreatePriceService(productPort(), pricePort, pricePort, fixedClock());
        var command = new CreatePriceCommand(new SiteId(UUID.randomUUID()), product.id(), "pro-monthly",
            PriceType.SUBSCRIPTION, Money.of(1999, "EUR"), BillingInterval.MONTH, 14, TaxMode.INCLUSIVE, null);

        assertThatThrownBy(() -> service.create(command))
            .isInstanceOf(DomainValidationException.class)
            .hasMessage("product does not belong to site");
        assertThat(pricePort.allocations).isZero();
        assertThat(pricePort.saved).isNull();
        assertThat(pricePort.prices).isEmpty();
    }

    @Test
    void allocatesNextVersionForTheSameSiteAndPriceKey() {
        InMemoryPricePort pricePort = new InMemoryPricePort(3);
        CreatePriceService service = new CreatePriceService(productPort(), pricePort, pricePort,
            fixedClock());

        Price created = service.create(subscriptionCommand("pro-monthly"));

        assertThat(created.version()).isEqualTo(3);
        assertThat(pricePort.saved).isEqualTo(created);
        assertThat(created.createdAt()).isEqualTo(Instant.parse("2026-09-12T12:00:00Z"));
    }

    private CreatePriceCommand subscriptionCommand(String key) {
        return new CreatePriceCommand(siteId, product.id(), key, PriceType.SUBSCRIPTION,
            Money.of(1999, "EUR"), BillingInterval.MONTH, 14, TaxMode.INCLUSIVE, "txcd_10000000");
    }

    private LoadProductPort productPort() {
        return new LoadProductPort() {
            @Override public Optional<Product> findById(ProductId id) {
                return product.id().equals(id) ? Optional.of(product) : Optional.empty();
            }
            @Override public List<Product> findBySiteId(SiteId id) { return List.of(product); }
        };
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);
    }

    private static final class InMemoryPricePort implements LoadPricePort, SavePricePort {
        private final int nextVersion;
        private final List<Price> prices = new ArrayList<>();
        private Price saved;
        private int allocations;

        private InMemoryPricePort(int nextVersion) { this.nextVersion = nextVersion; }
        @Override public Optional<Price> findById(PriceId id) {
            return prices.stream().filter(price -> price.id().equals(id)).findFirst();
        }
        @Override public List<Price> findBySiteId(SiteId siteId) { return List.copyOf(prices); }
        @Override public Optional<Price> findCurrentlyPublished(SiteId siteId, PriceKey key) {
            return prices.stream().filter(p -> p.siteId().equals(siteId) && p.key().equals(key) && p.status() == PriceStatus.PUBLISHED)
                .max(java.util.Comparator.comparingInt(Price::version));
        }
        @Override public int nextVersion(SiteId siteId, PriceKey key) { allocations++; return nextVersion; }
        @Override public Price save(Price price) { saved = price; prices.add(price); return price; }
    }
}
