package dev.genericpayments.platform.admin.application.port.out;
import dev.genericpayments.platform.admin.domain.AuditEntry;
public interface AuditLogPort {
    void append(AuditEntry entry);
}
