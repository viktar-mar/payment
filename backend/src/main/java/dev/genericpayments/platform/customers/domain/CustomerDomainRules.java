package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.regex.Pattern;

final class CustomerDomainRules {
    private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{2,62}");
    private static final Pattern VERSIONED_DIGEST = Pattern.compile("v[1-9][0-9]{0,8}:[A-Za-z0-9_-]+");

    private CustomerDomainRules() {}

    static <T> T required(T value, String field) {
        if (value == null) throw new DomainValidationException(field + " is required");
        return value;
    }

    static String safeKey(String value, String field) {
        if (value == null || !SAFE_KEY.matcher(value).matches()) {
            throw new DomainValidationException(field + " must match [a-z0-9][a-z0-9-]{2,62}");
        }
        return value;
    }

    static String reference(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new DomainValidationException(field + " must be a non-blank reference of at most 512 characters");
        }
        return value;
    }

    static String digest(String value, String field) {
        if (value == null || value.length() > 512 || !VERSIONED_DIGEST.matcher(value).matches()) {
            throw new DomainValidationException(field + " must be a bounded versioned digest reference");
        }
        return value;
    }

    static Instant transitionTime(Instant now, Instant previous) {
        required(now, "transition time");
        if (now.isBefore(previous)) throw new DomainValidationException("transition time must not move backwards");
        return now;
    }
}
