package dev.genericpayments.platform.catalog.application.service;

import dev.genericpayments.platform.catalog.application.model.*;
import dev.genericpayments.platform.catalog.application.port.in.PricePublicationAudit;
import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.time.Clock;
import org.springframework.transaction.annotation.*;

/** Only local reads and writes occur in these short transactions. */
public class PricePublicationTransaction {
    private final LoadPricePort prices;
    private final SavePricePort savePrices;
    private final LoadProductPort products;
    private final LoadSitePort sites;
    private final LoadPaymentAccountPort accounts;
    private final ProviderCatalogMappingPort mappings;
    private final CatalogPublicationLockPort locks;
    private final AdminCatalogAuditPort audit;
    private final Clock clock;

    public PricePublicationTransaction(LoadPricePort prices, SavePricePort savePrices, LoadProductPort products,
            LoadSitePort sites, LoadPaymentAccountPort accounts, ProviderCatalogMappingPort mappings,
            CatalogPublicationLockPort locks, AdminCatalogAuditPort audit, Clock clock) {
        this.prices = prices; this.savePrices = savePrices; this.products = products; this.sites = sites;
        this.accounts = accounts; this.mappings = mappings; this.locks = locks; this.audit = audit; this.clock = clock;
    }

    public record Publication(PaymentAccount account, Site site, Product product, Price draft,
                              CatalogPublicationLockPort.Versions versions) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Publication prepare(PriceId id) {
        var current = load(id);
        if (current.draft().status() != PriceStatus.DRAFT) {
            throw new DomainValidationException("only draft prices can be published");
        }
        return current;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Price complete(Publication expected, ProviderProductRef productRef, ProviderPriceRef priceRef) {
        return complete(expected, productRef, priceRef, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Price complete(Publication expected, ProviderProductRef productRef, ProviderPriceRef priceRef,
                          PricePublicationAudit context) {
        locks.lock(expected.account().id(), expected.site().id(), expected.product().id(), expected.draft().id());
        var current = load(expected.draft().id());
        if (!current.account().equals(expected.account()) || !current.site().equals(expected.site())
                || !current.product().equals(expected.product())
                || current.versions().account() != expected.versions().account()
                || current.versions().site() != expected.versions().site()
                || current.versions().product() != expected.versions().product()) throw conflict();
        if (current.draft().status() == PriceStatus.PUBLISHED) {
            // Exactly one ordinary publication update may have won since prepare.
            if (current.versions().price() != expected.versions().price() + 1
                    || !current.draft().equals(expected.draft().publish(current.draft().publishedAt()))
                    || mappings.findProduct(current.account().id(), current.product().id()).filter(productRef::equals).isEmpty()
                    || mappings.findPrice(current.account().id(), current.draft().id()).filter(priceRef::equals).isEmpty()) throw conflict();
            return current.draft();
        }
        if (!current.draft().equals(expected.draft()) || !current.versions().equals(expected.versions())) throw conflict();
        var timestamp = clock.instant();
        mappings.save(new ProviderCatalogMapping(current.account().id(), current.product().id(), productRef,
            current.draft().id(), priceRef, timestamp));
        var published = savePrices.save(current.draft().publish(timestamp));
        if (context != null) audit.pricePublished(published, context);
        return published;
    }

    private Publication load(PriceId id) {
        var price = prices.findById(id).orElseThrow(() -> new DomainValidationException("price does not exist"));
        var product = products.findById(price.productId()).orElseThrow(() -> new DomainValidationException("product does not exist"));
        if (!product.siteId().equals(price.siteId())) throw conflict();
        if (product.status() != ProductStatus.ACTIVE) throw new DomainValidationException("product is not active");
        var site = sites.findById(price.siteId()).orElseThrow(() -> new DomainValidationException("site does not exist"));
        if (site.status() != SiteStatus.ACTIVE) throw new DomainValidationException("site is not active");
        var account = accounts.findById(site.paymentAccountId()).orElseThrow(() -> new DomainValidationException("payment account does not exist"));
        if (account.status() != PaymentAccountStatus.ACTIVE) throw new DomainValidationException("payment account is not active");
        if (account.provider() == PaymentProviderType.STRIPE && price.taxMode() == TaxMode.EXCLUSIVE) {
            throw new PaymentApplicationException("PAYMENT_CAPABILITY_UNAVAILABLE", 422, "Payment capability unavailable");
        }
        return new Publication(account, site, product, price, locks.versions(account.id(), site.id(), product.id(), price.id()));
    }

    private static PaymentApplicationException conflict() {
        return new PaymentApplicationException("PRICE_PUBLICATION_CONFLICT", 409, "Price publication conflicts with current catalog");
    }
}
