package dev.genericpayments.platform.customers.application.port.out;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
public interface CustomerBillingAnonymizationGuardPort { void requireAnonymizable(SiteId site,CustomerId customer); }
