package dev.genericpayments.platform.catalog.adapter.in.web;
import dev.genericpayments.platform.catalog.domain.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
public record PriceResponse(UUID id, @JsonProperty("site_id") UUID siteId, @JsonProperty("product_id") UUID productId,
                            @JsonProperty("price_key") String priceKey, int version, PriceType type,
                            @JsonProperty("amount_minor") long amountMinor, String currency,
                            @JsonProperty("billing_interval") BillingInterval billingInterval,
                            @JsonProperty("trial_days") int trialDays,
                            @JsonProperty("trial_payment_method_policy") TrialPaymentMethodPolicy trialPaymentMethodPolicy,
                            @JsonProperty("tax_mode") TaxMode taxMode,
                            @JsonProperty("tax_code") String taxCode, PriceStatus status,
                            @JsonProperty("provider_price_id") String providerPriceId) {
    static PriceResponse from(Price price, String providerPriceId) {
        return new PriceResponse(price.id().value(), price.siteId().value(), price.productId().value(),
            price.key().value(), price.version(), price.type(), price.money().minorUnits(), price.money().currency(),
            price.billingInterval(), price.trialDays(), price.trialPaymentMethodPolicy(), price.taxMode(), price.taxCode(),
            price.status(), providerPriceId);
    }
}
