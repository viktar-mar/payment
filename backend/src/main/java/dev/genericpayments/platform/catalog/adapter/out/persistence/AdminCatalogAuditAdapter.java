package dev.genericpayments.platform.catalog.adapter.out.persistence;

import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.admin.domain.AuditEntry;
import dev.genericpayments.platform.catalog.application.port.in.PricePublicationAudit;
import dev.genericpayments.platform.catalog.application.port.out.AdminCatalogAuditPort;
import dev.genericpayments.platform.catalog.domain.Price;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AdminCatalogAuditAdapter implements AdminCatalogAuditPort {
    private final AuditLogPort audit;
    private final Clock clock;
    public AdminCatalogAuditAdapter(AuditLogPort audit, Clock clock) { this.audit = audit; this.clock = clock; }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void pricePublished(Price price, PricePublicationAudit context) {
        audit.append(new AuditEntry(UUID.randomUUID(), "ADMIN", context.actorId().toString(),
            AuditAction.PRICE_PUBLISHED, "price", price.id().value().toString(), context.requestId(),
            context.sourceIp(), Map.of("price_key", price.key().value(), "version", Integer.toString(price.version())),
            clock.instant()));
    }
}
