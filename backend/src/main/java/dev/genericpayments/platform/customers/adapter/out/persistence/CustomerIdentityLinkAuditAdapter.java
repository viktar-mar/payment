package dev.genericpayments.platform.customers.adapter.out.persistence;

import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerIdentityLinkAuditPort;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class CustomerIdentityLinkAuditAdapter implements CustomerIdentityLinkAuditPort {
    private final AuditLogPort audits; private final Clock clock;
    public CustomerIdentityLinkAuditAdapter(AuditLogPort audits,Clock clock){this.audits=audits;this.clock=clock;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(Event event) {
        Map<String,String> details=new LinkedHashMap<>();
        details.put("site_id",event.siteId().value().toString());details.put("mode",event.mode().name());
        details.put("issuer_key",event.issuerKey());details.put("outcome",event.outcome());
        if(event.identityId()!=null)details.put("identity_id",event.identityId().value().toString());
        audits.append(new AuditEntry(UUID.randomUUID(),event.actorType(),event.actorId(),AuditAction.CUSTOMER_IDENTITY_LINKED,
            "CUSTOMER",event.customerId().value().toString(),event.requestId(),null,details,clock.instant()));
    }
}
