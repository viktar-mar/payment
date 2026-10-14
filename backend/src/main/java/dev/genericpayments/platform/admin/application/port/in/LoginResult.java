package dev.genericpayments.platform.admin.application.port.in;
import java.time.Instant;
public record LoginResult(String rawSessionToken, Instant expiresAt) {
    @Override public String toString() { return "LoginResult[token=REDACTED, expiresAt=" + expiresAt + "]"; }
}
