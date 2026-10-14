package dev.genericpayments.platform.catalog.application.model;

import dev.genericpayments.platform.shared.domain.DomainValidationException;

public record ProviderProductRef(String value) {
    public ProviderProductRef {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("provider product reference is required");
        }
    }
}
