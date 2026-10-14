package dev.genericpayments.platform.admin.application.port.out;
import dev.genericpayments.platform.admin.domain.AdminSession;
import dev.genericpayments.platform.admin.domain.AdminSessionId;
import java.time.Instant;
import java.util.Optional;
public interface AdminSessionPort {
    void create(AdminSession session);
    Optional<AdminSession> findByTokenHash(String hash);
    boolean touch(AdminSessionId id, Instant now, Instant expiresAt);
    boolean revoke(AdminSessionId id, Instant now);
}
