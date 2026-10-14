package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record EmailOtpChallengeId(UUID value) {
    public EmailOtpChallengeId {
        if (value == null) throw new DomainValidationException("email OTP challenge id is required");
    }
    @Override public String toString() { return value.toString(); }
}
