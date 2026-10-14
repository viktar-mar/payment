package dev.genericpayments.platform.catalog.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record ProductId(UUID value) {
    public ProductId {
        if (value == null) {
            throw new DomainValidationException("product id is required");
        }
    }
}
