package dev.genericpayments.platform.sites.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

public record SiteKey(String value) {
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{2,62}");

    public SiteKey {
        if (value == null) {
            throw new DomainValidationException("site key is required");
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(value).matches()) {
            throw new DomainValidationException("site key must match [a-z0-9][a-z0-9-]{2,62}");
        }
    }

    public static SiteKey of(String value) {
        return new SiteKey(value);
    }
}
