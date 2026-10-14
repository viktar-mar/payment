package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;

/** Redacts extension-owned data inside the caller's anonymization transaction. Failure must roll back the unit. */
public interface CustomerDataRedactionPort {
    void redactCustomerData(SiteId siteId,CustomerId customerId,Instant now);
}
