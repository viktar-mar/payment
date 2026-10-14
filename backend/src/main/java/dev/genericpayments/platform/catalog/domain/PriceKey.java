package dev.genericpayments.platform.catalog.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

public record PriceKey(String value) {
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{2,62}");

    public PriceKey {
        if (value == null) {
            throw new DomainValidationException("price key is required");
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(value).matches()) {
            throw new DomainValidationException("price key must match [a-z0-9][a-z0-9-]{2,62}");
        }
    }

    public static PriceKey of(String value) {
        return new PriceKey(value);
    }
}
