package dev.genericpayments.platform.catalog.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record PriceId(UUID value) {
    public PriceId {
        if (value == null) {
            throw new DomainValidationException("price id is required");
        }
    }
}
