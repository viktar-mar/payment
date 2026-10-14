package dev.genericpayments.platform.subscriptions.domain;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.*;
public record TrialEligibility(SiteId siteId,CustomerId customerId,ProductId productId,UUID reservedByOperation,Instant consumedAt,long version) {
    public TrialEligibility {Objects.requireNonNull(siteId);Objects.requireNonNull(customerId);Objects.requireNonNull(productId);if(version<0||consumedAt!=null&&reservedByOperation==null)throw new DomainValidationException("Invalid trial eligibility");}
}
