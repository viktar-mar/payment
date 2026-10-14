package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;

public interface CustomerIdentityLinkAuditPort {
    record Event(SiteId siteId,CustomerId customerId,CustomerIdentityId identityId,CustomerAuthenticationMode mode,
        String issuerKey,String actorType,String actorId,String outcome,String requestId,String sourceIp) {}
    void append(Event event);
}
