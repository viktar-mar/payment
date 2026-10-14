package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import java.util.UUID;

public interface VerifyEmailOtpUseCase {
    CustomerAuthenticationResult verify(Command command);
    record Command(UUID challengeId,String siteKey,String origin,String code) {
        @Override public String toString(){return "VerifyEmailOtpCommand[challengeId="+challengeId+", credentials=redacted]";}
    }
}
