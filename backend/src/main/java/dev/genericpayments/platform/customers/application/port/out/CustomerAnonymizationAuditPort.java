package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;

public interface CustomerAnonymizationAuditPort {
    record Event(SiteId siteId,CustomerId customerId,String actorType,String actorId,String requestId,String sourceIp,Instant occurredAt) {}
    void append(Event event);
}
