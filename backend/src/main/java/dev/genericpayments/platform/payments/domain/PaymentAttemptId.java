package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record PaymentAttemptId(UUID value) {
    public PaymentAttemptId {
        if (value == null) throw new DomainValidationException("payment attempt id is required");
    }
}
