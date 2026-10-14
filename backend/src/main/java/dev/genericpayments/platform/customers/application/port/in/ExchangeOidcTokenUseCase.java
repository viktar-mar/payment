package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.domain.AuthenticationIntent;
import java.util.UUID;

public interface ExchangeOidcTokenUseCase {
    CustomerAuthenticationResult exchange(Command command);
    record Command(UUID attemptId,String siteKey,String providerKey,String origin,AuthenticationIntent intent,String nonce,String token) {
        @Override public String toString() { return "ExchangeOidcTokenCommand[attemptId="+attemptId+", credentials=redacted]"; }
    }
}
