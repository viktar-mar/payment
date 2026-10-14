package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record CustomerId(UUID value) {
    public CustomerId {
        if (value == null) throw new DomainValidationException("customer id is required");
    }
}
