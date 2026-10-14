package dev.genericpayments.platform.admin.adapter.out.persistence;

import dev.genericpayments.platform.admin.domain.AdminUser;
import dev.genericpayments.platform.admin.domain.AdminUserId;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_users")
class AdminUserJpaEntity {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 120) private String username;
    @Column(name = "password_hash", nullable = false, length = 255) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AdminUser.Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Version private long version;
    protected AdminUserJpaEntity() {}
    AdminUserJpaEntity(AdminUser user) {
        id = user.id().value();
        username = user.username();
        passwordHash = user.passwordHash();
        status = user.status();
        createdAt = user.createdAt();
    }
    AdminUser toDomain() { return new AdminUser(new AdminUserId(id), username, passwordHash, status, createdAt); }
}
