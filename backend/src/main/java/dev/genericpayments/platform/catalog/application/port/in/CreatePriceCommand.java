package dev.genericpayments.platform.catalog.application.port.in;

import dev.genericpayments.platform.catalog.domain.BillingInterval;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.PriceType;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy;
import dev.genericpayments.platform.sites.domain.SiteId;

public record CreatePriceCommand(SiteId siteId, ProductId productId, String key, PriceType type,
                                 Money money, BillingInterval billingInterval, int trialDays,
                                 TrialPaymentMethodPolicy trialPaymentMethodPolicy, TaxMode taxMode, String taxCode) {
    public CreatePriceCommand(SiteId siteId, ProductId productId, String key, PriceType type,
                              Money money, BillingInterval billingInterval, int trialDays,
                              TaxMode taxMode, String taxCode) {
        this(siteId, productId, key, type, money, billingInterval, trialDays,
            TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL, taxMode, taxCode);
    }
}
