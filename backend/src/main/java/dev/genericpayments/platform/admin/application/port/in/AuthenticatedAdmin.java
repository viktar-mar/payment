package dev.genericpayments.platform.admin.application.port.in;
import dev.genericpayments.platform.admin.domain.AdminUserId;
import dev.genericpayments.platform.admin.domain.AdminSessionId;
import java.time.Instant;
public record AuthenticatedAdmin(AdminUserId id, String username, AdminSessionId sessionId, Instant expiresAt) {}
