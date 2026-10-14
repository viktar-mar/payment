package dev.genericpayments.platform.admin.application.service;

import dev.genericpayments.platform.admin.application.port.in.*;
import dev.genericpayments.platform.admin.application.port.out.*;
import dev.genericpayments.platform.admin.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AdminSessionService implements LoginAdminUseCase, LogoutAdminUseCase {
    private final AdminUserPort users;
    private final AdminSessionPort sessions;
    private final AuditLogPort audits;
    private final AdminPasswordPort passwords;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;

    public AdminSessionService(AdminUserPort users, AdminSessionPort sessions, AuditLogPort audits,
                               AdminPasswordPort passwords, Clock clock, SecureRandom random,
                               Duration idleTimeout, Duration absoluteTimeout) {
        this.users = users;
        this.sessions = sessions;
        this.audits = audits;
        this.passwords = passwords;
        this.clock = clock;
        this.random = random;
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
    }

    @Override
    public LoginResult login(LoginAdminCommand command, String requestId, String sourceIp) {
        var user = users.findByUsername(command.username());
        boolean matched = passwords.matches(command.password(), user.map(AdminUser::passwordHash).orElse(passwords.dummyHash()));
        Instant now = clock.instant();
        if (!matched || user.isEmpty() || user.get().status() != AdminUser.Status.ACTIVE) {
            audit(null, AuditAction.ADMIN_LOGIN_FAILED, requestId, sourceIp, now);
            throw new InvalidCredentialsException();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant absoluteExpiry = now.plus(absoluteTimeout);
        Instant expiry = earlier(now.plus(idleTimeout), absoluteExpiry);
        sessions.create(new AdminSession(new AdminSessionId(UUID.randomUUID()), user.get().id(), tokenHash(token),
            now, now, expiry, absoluteExpiry, null));
        audit(user.get().id(), AuditAction.ADMIN_LOGIN_SUCCEEDED, requestId, sourceIp, now);
        return new LoginResult(token, expiry);
    }

    public Optional<AuthenticatedAdmin> authenticate(String rawToken) {
        if (!validToken(rawToken)) return Optional.empty();
        Instant now = clock.instant();
        return sessions.findByTokenHash(tokenHash(rawToken)).filter(s -> s.isActiveAt(now)).flatMap(session ->
            users.findById(session.adminUserId()).filter(user -> user.status() == AdminUser.Status.ACTIVE)
                .flatMap(user -> {
                    Instant expiry = earlier(now.plus(idleTimeout), session.absoluteExpiresAt());
                    if (!sessions.touch(session.id(), now, expiry)) return Optional.empty();
                    return Optional.of(new AuthenticatedAdmin(user.id(), user.username(), session.id(), expiry));
                }));
    }

    @Override
    public void logout(String rawToken, String requestId, String sourceIp) {
        if (!validToken(rawToken)) return;
        Instant now = clock.instant();
        sessions.findByTokenHash(tokenHash(rawToken)).ifPresent(session -> {
            if (sessions.revoke(session.id(), now)) {
                audit(session.adminUserId(), AuditAction.ADMIN_LOGOUT, requestId, sourceIp, now);
            }
        });
    }

    private void audit(AdminUserId user, AuditAction action, String requestId, String sourceIp, Instant now) {
        audits.append(new AuditEntry(UUID.randomUUID(), user == null ? "ANONYMOUS" : "ADMIN",
            user == null ? "anonymous" : user.value().toString(), action, "ADMIN_SESSION", null,
            requestId, sourceIp, Map.of(), now));
    }

    private static boolean validToken(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }

    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }

    private static String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
