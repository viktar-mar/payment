package dev.genericpayments.platform.admin.domain;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
public record AuditEntry(UUID id, String actorType, String actorId, AuditAction action,
                         String resourceType, String resourceId, String requestId,
                         String sourceIp, Map<String, String> details, Instant occurredAt) {
    public AuditEntry { details = Map.copyOf(details); }
}
