package dev.genericpayments.platform.admin.domain;
import java.time.Instant;
public record AdminSession(AdminSessionId id, AdminUserId adminUserId, String tokenHash,
                           Instant createdAt, Instant lastSeenAt, Instant expiresAt,
                           Instant absoluteExpiresAt, Instant revokedAt) {
    public boolean isActiveAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt) && now.isBefore(absoluteExpiresAt);
    }
}
