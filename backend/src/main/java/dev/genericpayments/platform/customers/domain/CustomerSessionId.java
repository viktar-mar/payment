package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record CustomerSessionId(UUID value) {
    public CustomerSessionId {
        if (value == null) throw new DomainValidationException("customer session id is required");
    }
}
