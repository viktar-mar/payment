package dev.genericpayments.platform.catalog.application.model;

import dev.genericpayments.platform.shared.domain.DomainValidationException;

public record ProviderPriceRef(String value) {
    public ProviderPriceRef {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("provider price reference is required");
        }
    }
}
