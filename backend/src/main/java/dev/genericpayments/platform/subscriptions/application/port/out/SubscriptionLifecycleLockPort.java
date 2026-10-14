package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
public interface SubscriptionLifecycleLockPort { /** Requires an ambient transaction. */ void lockActive(SiteId site,CustomerId customer); }
