package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.UUID;

/** Immutable invoice terms; actual cash may differ from the recurring Price amount. */
public record SubscriptionBillingContext(UUID subscriptionId, UUID billingRecordId, Kind kind,
    ProductId productId, PriceId priceId, int priceVersion, PriceId previousPriceId,
    Integer previousPriceVersion, Instant periodStart, Instant periodEnd, String providerInvoiceReference) {
    public enum Kind { INITIAL, RENEWAL, PRORATION }
    public SubscriptionBillingContext {
        PaymentDomainRules.required(subscriptionId,"subscription id");
        PaymentDomainRules.required(billingRecordId,"billing record id");
        PaymentDomainRules.required(kind,"billing kind");
        PaymentDomainRules.required(productId,"product id");
        PaymentDomainRules.required(priceId,"price id");
        PaymentDomainRules.required(periodStart,"period start");
        PaymentDomainRules.required(periodEnd,"period end");
        if(priceVersion<=0 || (previousPriceId==null)!=(previousPriceVersion==null)
                || previousPriceVersion!=null&&previousPriceVersion<=0 || !periodStart.isBefore(periodEnd))
            throw new DomainValidationException("Invalid subscription billing context");
        PaymentDomainRules.text(providerInvoiceReference,"provider invoice reference",255);
        if(!providerInvoiceReference.matches("[A-Za-z0-9_.:/-]+"))
            throw new DomainValidationException("Invalid provider invoice reference");
    }
}
