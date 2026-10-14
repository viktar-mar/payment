package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.domain.AuthenticationIntent;

public interface ExchangeSiteTokenUseCase {
    CustomerAuthenticationResult exchange(Command command);
    record Command(String siteKey,String providerKey,String origin,AuthenticationIntent intent,String token) {
        @Override public String toString() { return "ExchangeSiteTokenCommand[credentials=redacted]"; }
    }
}
