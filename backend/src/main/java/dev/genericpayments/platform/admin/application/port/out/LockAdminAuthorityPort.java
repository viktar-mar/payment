package dev.genericpayments.platform.admin.application.port.out;
import dev.genericpayments.platform.admin.domain.*;
import java.util.Optional;
/** Mandatory transaction. Lock user before session, after the caller's Site/Customer lifecycle locks. */
public interface LockAdminAuthorityPort {
    Optional<AdminUser> lockUser(AdminUserId id);
    Optional<AdminSession> lockSession(AdminSessionId id);
}
