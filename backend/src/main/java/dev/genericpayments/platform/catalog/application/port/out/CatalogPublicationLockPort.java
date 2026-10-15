package dev.genericpayments.platform.catalog.application.port.out;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;

/** Existing persistence version tokens, independent of the public catalog version. */
public interface CatalogPublicationLockPort {
    record Versions(long account, long site, long product, long price) {}
    Versions versions(PaymentAccountId account, SiteId site, ProductId product, PriceId price);
    /** Existing transaction required; locks account, site, product, price in that order and rechecks ownership. */
    Versions lock(PaymentAccountId account, SiteId site, ProductId product, PriceId price);
}
