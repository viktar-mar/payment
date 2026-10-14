package dev.genericpayments.platform.admin.adapter.out.persistence;

import dev.genericpayments.platform.admin.application.port.out.*;
import dev.genericpayments.platform.admin.domain.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class AdminPersistenceAdapter implements AdminUserPort, AdminSessionPort, AuditLogPort, LockAdminAuthorityPort {
    private final EntityManager entityManager;
    public AdminPersistenceAdapter(EntityManager entityManager) { this.entityManager = entityManager; }
    @Override @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<AdminUser> lockUser(AdminUserId id) {
        var entity=entityManager.find(AdminUserJpaEntity.class,id.value(),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(entity!=null)entityManager.refresh(entity,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return Optional.ofNullable(entity).map(AdminUserJpaEntity::toDomain);
    }
    @Override @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<AdminSession> lockSession(AdminSessionId id) {
        var entity=entityManager.find(AdminSessionJpaEntity.class,id.value(),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(entity!=null)entityManager.refresh(entity,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return Optional.ofNullable(entity).map(AdminSessionJpaEntity::toDomain);
    }

    @Override @Transactional(readOnly = true)
    public Optional<AdminUser> findByUsername(String username) {
        return entityManager.createQuery("select u from AdminUserJpaEntity u where u.username = :username", AdminUserJpaEntity.class)
            .setParameter("username", username).getResultStream().findFirst().map(AdminUserJpaEntity::toDomain);
    }
    @Override @Transactional(readOnly = true)
    public Optional<AdminUser> findById(AdminUserId id) {
        return Optional.ofNullable(entityManager.find(AdminUserJpaEntity.class, id.value())).map(AdminUserJpaEntity::toDomain);
    }
    @Override @Transactional(readOnly = true)
    public boolean anyUserExists() {
        return !entityManager.createQuery("select u.id from AdminUserJpaEntity u", java.util.UUID.class).setMaxResults(1).getResultList().isEmpty();
    }
    @Override public void createFirstIfAbsent(AdminUser user) {
        // Serialize the empty-table check across simultaneous application instances.
        entityManager.createNativeQuery("lock table admin_users in exclusive mode").executeUpdate();
        if (!anyUserExists()) entityManager.persist(new AdminUserJpaEntity(user));
    }
    @Override public void create(AdminSession session) { entityManager.persist(new AdminSessionJpaEntity(session)); }
    @Override @Transactional(readOnly = true)
    public Optional<AdminSession> findByTokenHash(String hash) {
        return entityManager.createQuery("select s from AdminSessionJpaEntity s where s.tokenHash = :hash", AdminSessionJpaEntity.class)
            .setParameter("hash", hash).getResultStream().findFirst().map(AdminSessionJpaEntity::toDomain);
    }
    @Override public boolean touch(AdminSessionId id, Instant now, Instant expiresAt) {
        // Reverse request completion order must not reject an active session or move timestamps backwards.
        return entityManager.createQuery("""
            update AdminSessionJpaEntity s
                set s.lastSeenAt = greatest(s.lastSeenAt, :now),
                    s.expiresAt = least(s.absoluteExpiresAt, greatest(s.expiresAt, :expiry))
            where s.id = :id and s.revokedAt is null and s.expiresAt > :now
                and s.absoluteExpiresAt > :now
            """).setParameter("id", id.value()).setParameter("now", now).setParameter("expiry", expiresAt)
            .executeUpdate() == 1;
    }
    @Override public boolean revoke(AdminSessionId id, Instant now) {
        return entityManager.createQuery("update AdminSessionJpaEntity s set s.revokedAt = :now where s.id = :id and s.revokedAt is null")
            .setParameter("id", id.value()).setParameter("now", now).executeUpdate() == 1;
    }
    @Override public void append(AuditEntry entry) { entityManager.persist(new AuditEntryJpaEntity(entry)); }
}
