package dev.genericpayments.platform.catalog.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

public record ProductKey(String value) {
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{2,62}");

    public ProductKey {
        if (value == null) {
            throw new DomainValidationException("product key is required");
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(value).matches()) {
            throw new DomainValidationException("product key must match [a-z0-9][a-z0-9-]{2,62}");
        }
    }

    public static ProductKey of(String value) {
        return new ProductKey(value);
    }
}
