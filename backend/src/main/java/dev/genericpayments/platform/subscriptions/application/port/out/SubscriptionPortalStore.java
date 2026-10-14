package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import java.time.Instant;
import java.util.UUID;
public interface SubscriptionPortalStore {
    void save(SiteId site,CustomerId customer,UUID subscription,UUID publicOperation,Instant expiresAt);
    boolean valid(SiteId site,CustomerId customer,UUID subscription,UUID publicOperation,Instant now);
}
