package dev.genericpayments.platform.admin.adapter.out.persistence;

import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.admin.domain.AuditEntry;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
class AuditEntryJpaEntity {
    @Id private UUID id;
    @Column(name = "actor_type", nullable = false, length = 24) private String actorType;
    @Column(name = "actor_id", nullable = false, length = 255) private String actorId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) private AuditAction action;
    @Column(name = "resource_type", length = 64) private String resourceType;
    @Column(name = "resource_id", length = 255) private String resourceId;
    @Column(name = "request_id", nullable = false, length = 64) private String requestId;
    @Column(name = "source_ip", length = 64) private String sourceIp;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String, String> details;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    protected AuditEntryJpaEntity() {}
    AuditEntryJpaEntity(AuditEntry entry) {
        id = entry.id();
        actorType = entry.actorType();
        actorId = entry.actorId();
        action = entry.action();
        resourceType = entry.resourceType();
        resourceId = entry.resourceId();
        requestId = entry.requestId();
        sourceIp = entry.sourceIp();
        details = entry.details();
        occurredAt = entry.occurredAt();
    }
}
