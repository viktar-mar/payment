package dev.genericpayments.platform.customers.adapter.out.persistence;

import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerAnonymizationAuditPort;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class CustomerAnonymizationAuditAdapter implements CustomerAnonymizationAuditPort {
    private final AuditLogPort audits;
    public CustomerAnonymizationAuditAdapter(AuditLogPort audits){this.audits=audits;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(Event event) {
        audits.append(new AuditEntry(UUID.randomUUID(),event.actorType(),event.actorId(),AuditAction.CUSTOMER_ANONYMIZED,
            "CUSTOMER",event.customerId().value().toString(),event.requestId(),null,
            Map.of("site_id",event.siteId().value().toString(),"outcome","ANONYMIZED"),event.occurredAt()));
    }
}
