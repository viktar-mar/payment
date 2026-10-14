package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;

/** Requires the caller's Site -> Customer locks. Revokes sessions, related OTPs and owned identity proofs. */
public interface CustomerAccessRevocationPort {
    void revokeCustomerAccess(SiteId siteId,CustomerId customerId,Instant now);
}
