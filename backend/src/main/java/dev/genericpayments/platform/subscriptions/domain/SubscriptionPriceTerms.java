package dev.genericpayments.platform.subscriptions.domain;

import dev.genericpayments.platform.catalog.domain.BillingInterval;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.PriceKey;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Objects;

public record SubscriptionPriceTerms(PriceId priceId, String priceKey, int version,
                                     ProductId productId, Money regularAmount, BillingInterval interval,
                                     int trialDays, TrialPaymentMethodPolicy trialPolicy, TaxMode taxMode) {
    public SubscriptionPriceTerms {
        Objects.requireNonNull(priceId, "priceId");
        priceKey = new PriceKey(priceKey).value();
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(regularAmount, "regularAmount");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(trialPolicy, "trialPolicy");
        Objects.requireNonNull(taxMode, "taxMode");
        if (version <= 0 || trialDays < 0) {
            throw new DomainValidationException("Invalid subscription price terms");
        }
    }
}
