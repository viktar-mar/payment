package dev.genericpayments.platform.admin.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.out.AdminPasswordPort;
import dev.genericpayments.platform.admin.application.port.out.AdminUserPort;
import dev.genericpayments.platform.admin.domain.AdminUser;
import dev.genericpayments.platform.admin.domain.AdminUserId;
import java.time.Clock;
import java.util.UUID;

public final class AdminBootstrap {
    private final AdminBootstrapProperties properties;
    private final AdminUserPort users;
    private final AdminPasswordPort passwords;
    private final Clock clock;
    public AdminBootstrap(AdminBootstrapProperties properties, AdminUserPort users, AdminPasswordPort passwords, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
    }
    public void initialize() {
        if (users.anyUserExists() || properties.username() == null || properties.username().isBlank()
            || properties.password() == null || properties.password().isBlank()) return;
        users.createFirstIfAbsent(new AdminUser(new AdminUserId(UUID.randomUUID()), properties.username(),
            passwords.hash(properties.password()), AdminUser.Status.ACTIVE, clock.instant()));
    }
}
