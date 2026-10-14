package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.domain.AuthenticationIntent;
import java.time.Instant;
import java.util.UUID;

public interface RequestEmailOtpUseCase {
    Result request(Command command);
    record Command(String siteKey,String origin,AuthenticationIntent intent,String email,String sourceAddress) {
        @Override public String toString(){return "RequestEmailOtpCommand[credentials=redacted]";}
    }
    record Result(UUID challengeId,Instant expiresAt) {}
}
