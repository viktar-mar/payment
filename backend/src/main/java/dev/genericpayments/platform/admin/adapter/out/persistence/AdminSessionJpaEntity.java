package dev.genericpayments.platform.admin.adapter.out.persistence;

import dev.genericpayments.platform.admin.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "admin_sessions")
class AdminSessionJpaEntity {
    @Id private UUID id;
    @Column(name = "admin_user_id", nullable = false) private UUID adminUserId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)") private String tokenHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "absolute_expires_at", nullable = false) private Instant absoluteExpiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    protected AdminSessionJpaEntity() {}
    AdminSessionJpaEntity(AdminSession session) {
        id = session.id().value();
        adminUserId = session.adminUserId().value();
        tokenHash = session.tokenHash();
        createdAt = session.createdAt();
        lastSeenAt = session.lastSeenAt();
        expiresAt = session.expiresAt();
        absoluteExpiresAt = session.absoluteExpiresAt();
        revokedAt = session.revokedAt();
    }
    AdminSession toDomain() {
        return new AdminSession(new AdminSessionId(id), new AdminUserId(adminUserId), tokenHash,
            createdAt, lastSeenAt, expiresAt, absoluteExpiresAt, revokedAt);
    }
}
