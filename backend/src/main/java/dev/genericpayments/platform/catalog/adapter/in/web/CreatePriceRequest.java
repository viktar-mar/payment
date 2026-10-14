package dev.genericpayments.platform.catalog.adapter.in.web;
import dev.genericpayments.platform.catalog.domain.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
public record CreatePriceRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9-]{2,62}") @JsonProperty("price_key") String priceKey,
                                 @NotNull PriceType type, @NotNull @Positive @JsonProperty("amount_minor") Long amountMinor,
                                 @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
                                 @JsonProperty("billing_interval") BillingInterval billingInterval,
                                 @NotNull @PositiveOrZero @JsonProperty("trial_days") Integer trialDays,
                                 @JsonProperty("trial_payment_method_policy") TrialPaymentMethodPolicy trialPaymentMethodPolicy,
                                 @NotNull @JsonProperty("tax_mode") TaxMode taxMode,
                                 @Size(max = 64) @JsonProperty("tax_code") String taxCode) {
    public CreatePriceRequest {
        if (trialPaymentMethodPolicy == null) trialPaymentMethodPolicy = TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL;
    }

    public CreatePriceRequest(String priceKey, PriceType type, Long amountMinor, String currency,
                               BillingInterval billingInterval, Integer trialDays, TaxMode taxMode, String taxCode) {
        this(priceKey, type, amountMinor, currency, billingInterval, trialDays,
            TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL, taxMode, taxCode);
    }

    @AssertTrue(message = "billing interval must match price type")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBillingValid() {
        return type == null || (type == PriceType.SUBSCRIPTION ? billingInterval != null
            : billingInterval == null && (trialDays == null || trialDays == 0));
    }
}
