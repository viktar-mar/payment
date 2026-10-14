package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import java.util.UUID;
import java.time.Instant;
public interface TrialEligibilityStore {
    java.util.Optional<dev.genericpayments.platform.subscriptions.domain.TrialEligibility> find(SiteId site,CustomerId customer,ProductId product);
    boolean isEligible(SiteId site,CustomerId customer,ProductId product);
    boolean reserve(SiteId site,CustomerId customer,ProductId product,UUID operation);
    void consume(UUID operation,Instant at);
    void releaseConfirmedFailure(UUID operation);
}
