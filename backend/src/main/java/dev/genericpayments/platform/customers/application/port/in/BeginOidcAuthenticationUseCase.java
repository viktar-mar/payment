package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.domain.AuthenticationIntent;
import java.time.Instant;
import java.util.UUID;

public interface BeginOidcAuthenticationUseCase {
    Result begin(Command command);
    record Command(String siteKey,String providerKey,String origin,AuthenticationIntent intent) {
        @Override public String toString() { return "BeginOidcAuthenticationCommand[redacted]"; }
    }
    record Result(UUID attemptId,String nonce,Instant expiresAt) {
        @Override public String toString() { return "BeginOidcAuthenticationResult[attemptId="+attemptId+", nonce=redacted]"; }
    }
}
