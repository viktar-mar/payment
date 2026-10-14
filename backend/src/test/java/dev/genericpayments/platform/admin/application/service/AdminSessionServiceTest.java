package dev.genericpayments.platform.admin.application.service;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.admin.adapter.out.security.Argon2AdminPasswordAdapter;
import dev.genericpayments.platform.admin.application.port.in.LoginAdminCommand;
import dev.genericpayments.platform.admin.application.port.out.*;
import dev.genericpayments.platform.admin.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminSessionServiceTest {
    private static final Instant START = Instant.parse("2026-09-13T10:00:00Z");
    private final MemoryStore store = new MemoryStore();
    private final MutableClock clock = new MutableClock();
    private final CountingPasswords passwords = new CountingPasswords();
    private AdminSessionService service;

    @BeforeEach
    void setUp() {
        store.save(new AdminUser(new AdminUserId(UUID.randomUUID()), "admin",
            passwords.hash("correct-password"), AdminUser.Status.ACTIVE, START));
        service = new AdminSessionService(store, store, store, passwords, clock,
            new SecureRandom(), Duration.ofHours(8), Duration.ofHours(24));
    }

    @Test
    void validPasswordCreatesHashedOpaqueSessionAndAuditEntry() throws Exception {
        var result = login();
        assertThat(result.rawSessionToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(result.rawSessionToken())).hasSize(32);
        var session = store.sessions.values().iterator().next();
        assertThat(session.tokenHash()).isEqualTo(HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(result.rawSessionToken().getBytes(StandardCharsets.UTF_8))));
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-09-13T18:00:00Z"));
        assertThat(session.absoluteExpiresAt()).isEqualTo(Instant.parse("2026-09-14T10:00:00Z"));
        assertThat(store.audits.getLast().action()).isEqualTo(AuditAction.ADMIN_LOGIN_SUCCEEDED);
        assertThat(store.audits.getLast().requestId()).isEqualTo("request-1");
        assertThat(store.audits.getLast().occurredAt()).isEqualTo(START);
        assertThat(store.audits.toString()).doesNotContain("correct-password", result.rawSessionToken(), session.tokenHash());
        assertThat(result.toString()).doesNotContain(result.rawSessionToken());
        assertThat(new LoginAdminCommand("admin", "correct-password").toString()).doesNotContain("correct-password");
        assertThat(login().rawSessionToken()).isNotEqualTo(result.rawSessionToken());
    }

    @Test
    void invalidPasswordAndUnknownAndDisabledUsersHaveSameFailureAndOnePasswordVerification() {
        for (var username : List.of("admin", "missing")) {
            int before = passwords.verifications;
            assertThatThrownBy(() -> service.login(new LoginAdminCommand(username, "wrong"), "r1", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage("Invalid credentials");
            assertThat(passwords.verifications).isEqualTo(before + 1);
        }
        var admin = store.users.values().iterator().next();
        store.save(new AdminUser(admin.id(), admin.username(), admin.passwordHash(), AdminUser.Status.DISABLED, START));
        assertThatThrownBy(this::login).isInstanceOf(InvalidCredentialsException.class).hasMessage("Invalid credentials");
        assertThat(store.sessions).isEmpty();
        assertThat(store.audits).hasSize(3).allMatch(a -> a.action() == AuditAction.ADMIN_LOGIN_FAILED);
        assertThat(store.audits.toString()).doesNotContain("wrong", "correct-password", "missing");
        assertThat(passwords.hashesVerified.get(1)).startsWith("$argon2id$");
    }

    @Test
    void repeatedUnknownUsersUseTheSameDummyHash() {
        for (var username : List.of("missing", "also-missing")) {
            assertThatThrownBy(() -> service.login(new LoginAdminCommand(username, "wrong"), "r", null))
                .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(passwords.hashesVerified).hasSize(2);
        assertThat(passwords.hashesVerified.getFirst()).isEqualTo(passwords.hashesVerified.getLast());
    }

    @Test
    void activeSessionSlidesIdleExpiryButNeverPastAbsoluteCap() {
        var token = login().rawSessionToken();
        for (int hours : List.of(7, 14, 21)) {
            clock.now = START.plus(Duration.ofHours(hours));
            assertThat(service.authenticate(token)).isPresent();
        }
        var session = store.sessions.values().iterator().next();
        assertThat(session.lastSeenAt()).isEqualTo(Instant.parse("2026-09-14T07:00:00Z"));
        assertThat(session.expiresAt()).isEqualTo(Instant.parse("2026-09-14T10:00:00Z"));
        clock.now = START.plus(Duration.ofHours(24));
        assertThat(service.authenticate(token)).isEmpty();
    }

    @Test
    void sessionRejectsAtIdleBoundaryAndRejectsMalformedOrUnknownTokens() {
        var token = login().rawSessionToken();
        clock.now = START.plus(Duration.ofHours(8));
        assertThat(service.authenticate(token)).isEmpty();
        for (var bad : List.of("", "bad", "x".repeat(43), "!".repeat(43), "x".repeat(10000))) {
            assertThat(service.authenticate(bad)).isEmpty();
        }
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    void logoutRevokesSessionAndAuditsWithoutSecrets() {
        var token = login().rawSessionToken();
        clock.now = START.plusSeconds(10);
        service.logout(token, "logout-request", "127.0.0.1");
        assertThat(service.authenticate(token)).isEmpty();
        assertThat(store.sessions.values().iterator().next().revokedAt()).isEqualTo(clock.now);
        assertThat(store.audits.getLast().action()).isEqualTo(AuditAction.ADMIN_LOGOUT);
        assertThat(store.audits.getLast().requestId()).isEqualTo("logout-request");
        assertThat(store.audits.toString()).doesNotContain(token, "correct-password");
    }

    @Test
    void disabledAdminCannotUsePreviouslyIssuedSession() {
        var token = login().rawSessionToken();
        var admin = store.users.values().iterator().next();
        store.save(new AdminUser(admin.id(), admin.username(), admin.passwordHash(), AdminUser.Status.DISABLED, START));
        assertThat(service.authenticate(token)).isEmpty();
    }

    private dev.genericpayments.platform.admin.application.port.in.LoginResult login() {
        return service.login(new LoginAdminCommand("admin", "correct-password"), "request-1", "127.0.0.1");
    }

    private static final class CountingPasswords implements AdminPasswordPort {
        final Argon2AdminPasswordAdapter delegate = new Argon2AdminPasswordAdapter();
        int verifications;
        final List<String> hashesVerified = new ArrayList<>();
        public String hash(String password) { return delegate.hash(password); }
        public boolean matches(String password, String hash) {
            verifications++;
            hashesVerified.add(hash);
            return delegate.matches(password, hash);
        }
        public String dummyHash() { return delegate.dummyHash(); }
    }

    private static final class MutableClock extends Clock {
        Instant now = START;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private static final class MemoryStore implements AdminUserPort, AdminSessionPort, AuditLogPort {
        final Map<AdminUserId, AdminUser> users = new HashMap<>();
        final Map<AdminSessionId, AdminSession> sessions = new HashMap<>();
        final List<AuditEntry> audits = new ArrayList<>();
        public Optional<AdminUser> findByUsername(String username) { return users.values().stream().filter(u -> u.username().equals(username)).findFirst(); }
        public Optional<AdminUser> findById(AdminUserId id) { return Optional.ofNullable(users.get(id)); }
        public boolean anyUserExists() { return !users.isEmpty(); }
        public void save(AdminUser user) { users.put(user.id(), user); }
        public void createFirstIfAbsent(AdminUser user) { if (users.isEmpty()) save(user); }
        public void create(AdminSession session) { sessions.put(session.id(), session); }
        public Optional<AdminSession> findByTokenHash(String hash) { return sessions.values().stream().filter(s -> s.tokenHash().equals(hash)).findFirst(); }
        public boolean touch(AdminSessionId id, Instant now, Instant expiry) {
            var session = sessions.get(id);
            if (!session.isActiveAt(now)) return false;
            sessions.put(id, new AdminSession(id, session.adminUserId(), session.tokenHash(), session.createdAt(), now,
                expiry, session.absoluteExpiresAt(), session.revokedAt()));
            return true;
        }
        public boolean revoke(AdminSessionId id, Instant now) {
            var session = sessions.get(id);
            if (session.revokedAt() != null) return false;
            sessions.put(id, new AdminSession(id, session.adminUserId(), session.tokenHash(), session.createdAt(),
                session.lastSeenAt(), session.expiresAt(), session.absoluteExpiresAt(), now));
            return true;
        }
        public void append(AuditEntry entry) { audits.add(entry); }
    }
}
