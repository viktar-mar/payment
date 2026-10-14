package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record RefundId(UUID value) {
    public RefundId {
        if (value == null) throw new DomainValidationException("refund id is required");
    }
}
