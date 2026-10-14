package dev.genericpayments.platform.admin.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.genericpayments.platform.admin.application.port.in.LoginAdminCommand;
import dev.genericpayments.platform.admin.application.port.out.AdminPasswordPort;
import dev.genericpayments.platform.admin.application.port.out.AdminSessionPort;
import dev.genericpayments.platform.admin.application.port.out.AdminUserPort;
import dev.genericpayments.platform.admin.application.service.AdminSessionService;
import dev.genericpayments.platform.admin.domain.AdminUser;
import dev.genericpayments.platform.admin.domain.AdminUserId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminSessionPersistenceIntegrationTest extends PostgresIntegrationTest {
    @Autowired AdminSessionService service;
    @Autowired AdminSessionPort sessions;
    @Autowired AdminUserPort users;
    @Autowired AdminPasswordPort passwords;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void createAdmin() {
        clearDatabase();
        users.createFirstIfAbsent(new AdminUser(new AdminUserId(UUID.randomUUID()), "admin",
            passwords.hash("local-password"), AdminUser.Status.ACTIVE, INITIAL_TIME));
    }

    @Test
    void reverseTimestampRefreshesBothAuthenticateAndKeepDatabaseTimestampsMonotonic() {
        var login = service.login(new LoginAdminCommand("admin", "local-password"), "login", "127.0.0.1");
        // A request captured t1, but another request captured t2 and committed its refresh first.
        TestClock.set(INITIAL_TIME.plus(Duration.ofHours(2)));
        assertThat(service.authenticate(login.rawSessionToken())).isPresent();
        TestClock.set(INITIAL_TIME.plus(Duration.ofHours(1)));
        assertThat(service.authenticate(login.rawSessionToken())).isPresent();

        assertThat(jdbc.queryForObject("select last_seen_at from admin_sessions", java.sql.Timestamp.class).toInstant())
            .isEqualTo(Instant.parse("2026-09-13T12:15:30Z"));
        assertThat(jdbc.queryForObject("select expires_at from admin_sessions", java.sql.Timestamp.class).toInstant())
            .isEqualTo(Instant.parse("2026-09-13T20:15:30Z"));

        var session = sessions.findByTokenHash(jdbc.queryForObject("select token_hash from admin_sessions", String.class)).orElseThrow();
        assertThat(sessions.revoke(session.id(), INITIAL_TIME.plus(Duration.ofHours(2)))).isTrue();
        assertThat(sessions.touch(session.id(), INITIAL_TIME.plus(Duration.ofHours(1)), INITIAL_TIME.plus(Duration.ofHours(9))))
            .isFalse();
        assertThat(service.authenticate(login.rawSessionToken())).isEmpty();
    }

    @Test
    void persistenceRefreshCapsExpiryAndCannotReviveExpiredSession() {
        service.login(new LoginAdminCommand("admin", "local-password"), "login", "127.0.0.1");
        var session = sessions.findByTokenHash(jdbc.queryForObject("select token_hash from admin_sessions", String.class)).orElseThrow();
        assertThat(sessions.touch(session.id(), INITIAL_TIME.plusSeconds(1), INITIAL_TIME.plus(Duration.ofHours(48))))
            .isTrue();
        assertThat(jdbc.queryForObject("select expires_at from admin_sessions", java.sql.Timestamp.class).toInstant())
            .isEqualTo(Instant.parse("2026-09-14T10:15:30Z"));
        assertThat(sessions.touch(session.id(), INITIAL_TIME.plus(Duration.ofHours(24)), INITIAL_TIME.plus(Duration.ofHours(48))))
            .isFalse();
    }
}
