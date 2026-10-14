package dev.genericpayments.platform.shared.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminAuditFacade {
    private final AuditLogPort audit;
    private final Clock clock;
    public AdminAuditFacade(AuditLogPort audit, Clock clock) { this.audit = audit; this.clock = clock; }
    /** The caller's mutation and success audit commit or roll back together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String resourceType, UUID resourceId, AuthenticatedAdmin actor,
                       HttpServletRequest request, Map<String, String> details) {
        audit.append(new AuditEntry(UUID.randomUUID(), "ADMIN", actor.id().value().toString(), action,
            resourceType, resourceId.toString(), RequestIdFilter.requestId(request), request.getRemoteAddr(),
            details, clock.instant()));
    }
}
