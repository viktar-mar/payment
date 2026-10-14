package dev.genericpayments.platform.catalog.application.service;

import dev.genericpayments.platform.catalog.application.model.ProviderCatalogMapping;
import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
import dev.genericpayments.platform.catalog.application.port.in.PublishPriceUseCase;
import dev.genericpayments.platform.catalog.application.port.out.CatalogProviderPort;
import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.application.port.out.LoadProductPort;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.catalog.application.port.out.SavePricePort;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.PriceStatus;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

public class PublishPriceService implements PublishPriceUseCase {
    private final LoadPricePort loadPrices;
    private final SavePricePort savePrices;
    private final LoadProductPort loadProducts;
    private final LoadSitePort loadSites;
    private final LoadPaymentAccountPort loadPaymentAccounts;
    private final CatalogProviderPort provider;
    private final ProviderCatalogMappingPort mappings;
    private final Clock clock;

    public PublishPriceService(LoadPricePort loadPrices, SavePricePort savePrices,
                               LoadProductPort loadProducts, LoadSitePort loadSites,
                               LoadPaymentAccountPort loadPaymentAccounts, CatalogProviderPort provider,
                               ProviderCatalogMappingPort mappings, Clock clock) {
        this.loadPrices = Objects.requireNonNull(loadPrices);
        this.savePrices = Objects.requireNonNull(savePrices);
        this.loadProducts = Objects.requireNonNull(loadProducts);
        this.loadSites = Objects.requireNonNull(loadSites);
        this.loadPaymentAccounts = Objects.requireNonNull(loadPaymentAccounts);
        this.provider = Objects.requireNonNull(provider);
        this.mappings = Objects.requireNonNull(mappings);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Price publish(PriceId id) {
        Price draft = loadPrices.findById(id)
            .orElseThrow(() -> new DomainValidationException("price does not exist"));
        if (draft.status() != PriceStatus.DRAFT) {
            throw new DomainValidationException("only draft prices can be published");
        }
        var product = loadProducts.findById(draft.productId())
            .orElseThrow(() -> new DomainValidationException("product does not exist"));
        if (!product.siteId().equals(draft.siteId())) {
            throw new DomainValidationException("product does not belong to price site");
        }
        var site = loadSites.findById(draft.siteId())
            .orElseThrow(() -> new DomainValidationException("site does not exist"));
        if (site.status() != SiteStatus.ACTIVE) {
            throw new DomainValidationException("site is not active");
        }
        var account = loadPaymentAccounts.findById(site.paymentAccountId())
            .orElseThrow(() -> new DomainValidationException("payment account does not exist"));
        if (account.status() != PaymentAccountStatus.ACTIVE) {
            throw new DomainValidationException("payment account is not active");
        }

        ProviderProductRef productRef = mappings.findProduct(account.id(), product.id())
            .orElseGet(() -> provider.ensureProduct(account, product));
        ProviderPriceRef priceRef = mappings.findPrice(account.id(), draft.id())
            .orElseGet(() -> provider.createPrice(account, product, draft));
        Instant timestamp = clock.instant();
        mappings.save(new ProviderCatalogMapping(account.id(), product.id(), productRef, draft.id(), priceRef,
            timestamp));
        return savePrices.save(draft.publish(timestamp));
    }
}
