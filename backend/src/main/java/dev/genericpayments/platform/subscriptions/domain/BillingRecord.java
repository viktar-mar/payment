package dev.genericpayments.platform.subscriptions.domain;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.*;
public record BillingRecord(UUID id,SiteId siteId,UUID subscriptionId,PaymentAccountId accountId,String providerInvoiceReference,
    Kind kind,SubscriptionPriceTerms billedPrice,SubscriptionPriceTerms previousPrice,Subscription.Period period,
    long amountDueMinor,String currency,Settlement settlement,long version,Instant createdAt,Instant updatedAt) {
    public enum Kind { INITIAL, RENEWAL, PRORATION }
    public enum Settlement { OPEN, CONFIRMED_CASH, ZERO_AMOUNT, UNSUPPORTED, VOID }
    public BillingRecord {
        Objects.requireNonNull(id);Objects.requireNonNull(siteId);Objects.requireNonNull(subscriptionId);Objects.requireNonNull(accountId);Objects.requireNonNull(kind);
        Objects.requireNonNull(billedPrice);Objects.requireNonNull(period);Objects.requireNonNull(settlement);Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);
        if(providerInvoiceReference==null||providerInvoiceReference.length()>255||!providerInvoiceReference.matches("[A-Za-z0-9_.:/-]+"))throw new DomainValidationException("Invalid invoice reference");
        if(amountDueMinor<0||version<0||updatedAt.isBefore(createdAt)||!billedPrice.regularAmount().currency().equals(currency)
            ||previousPrice!=null&&(!previousPrice.productId().equals(billedPrice.productId())||!previousPrice.regularAmount().currency().equals(currency))
            ||settlement==Settlement.ZERO_AMOUNT&&amountDueMinor!=0||settlement==Settlement.CONFIRMED_CASH&&amountDueMinor==0)throw new DomainValidationException("Invalid billing record");
    }
}
