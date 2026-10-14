package dev.genericpayments.platform.catalog.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Objects;

public record Product(ProductId id, SiteId siteId, ProductKey key, String name, String description,
                      ProductStatus status, Instant createdAt, Instant updatedAt) {
    public Product {
        id = Objects.requireNonNull(id, "id");
        siteId = Objects.requireNonNull(siteId, "siteId");
        key = Objects.requireNonNull(key, "key");
        name = requireName(name);
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Product create(ProductId id, SiteId siteId, ProductKey key, String name,
                                 String description, Instant createdAt) {
        return new Product(id, siteId, key, name, description, ProductStatus.ACTIVE, createdAt, createdAt);
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new DomainValidationException("product name must be between 1 and 120 characters");
        }
        return value;
    }
}
