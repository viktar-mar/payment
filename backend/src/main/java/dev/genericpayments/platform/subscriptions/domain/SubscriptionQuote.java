package dev.genericpayments.platform.subscriptions.domain;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.catalog.domain.PriceId;
import java.time.Instant;
import java.util.UUID;
public record SubscriptionQuote(UUID id,SiteId siteId,UUID subscriptionId,String authorityScope,
    PriceId fromPriceId,int fromPriceVersion,SubscriptionPriceTerms target,ChangeTiming timing,
    long dueNowMinor,long nextAmountMinor,String currency,Instant effectiveAt,Instant expiresAt,
    boolean taxFinal,long subscriptionRevision,long policyVersion,String providerQuoteReference) {
    public SubscriptionQuote {
        java.util.Objects.requireNonNull(id);java.util.Objects.requireNonNull(siteId);java.util.Objects.requireNonNull(subscriptionId);
        java.util.Objects.requireNonNull(fromPriceId);java.util.Objects.requireNonNull(target);java.util.Objects.requireNonNull(timing);
        java.util.Objects.requireNonNull(effectiveAt);java.util.Objects.requireNonNull(expiresAt);
        if(fromPriceVersion<1||subscriptionRevision<0||policyVersion<0||nextAmountMinor<=0||!target.regularAmount().currency().equals(currency)
            ||authorityScope==null||!authorityScope.matches("[A-Za-z0-9_.:/-]{1,255}")
            ||providerQuoteReference==null||!providerQuoteReference.matches("[A-Za-z0-9_.:/-]{1,255}"))
            throw new dev.genericpayments.platform.shared.domain.DomainValidationException("Invalid subscription quote");
    }
}
