package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record CustomerIdentityId(UUID value) {
    public CustomerIdentityId {
        if (value == null) throw new DomainValidationException("customer identity id is required");
    }
}
