package dev.genericpayments.platform.admin.application.port.out;
import dev.genericpayments.platform.admin.domain.AdminUser;
import dev.genericpayments.platform.admin.domain.AdminUserId;
import java.util.Optional;
public interface AdminUserPort {
    Optional<AdminUser> findByUsername(String username);
    Optional<AdminUser> findById(AdminUserId id);
    boolean anyUserExists();
    void createFirstIfAbsent(AdminUser user);
}
