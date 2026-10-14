package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record PaymentId(UUID value) {
    public PaymentId {
        if (value == null) throw new DomainValidationException("payment id is required");
    }
}
