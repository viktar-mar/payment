package dev.genericpayments.platform.donations.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;

final class DonationDomainRules {
    private DonationDomainRules() {}

    static <T> T required(T value, String field) {
        if (value == null) throw new DomainValidationException(field + " is required");
        return value;
    }

    static String currency(String value) {
        try {
            return Currency.getInstance(value.toUpperCase(Locale.ROOT)).getCurrencyCode();
        } catch (RuntimeException exception) {
            throw new DomainValidationException("invalid campaign currency");
        }
    }

    static Instant transitionTime(Instant now, Instant previous) {
        required(now, "transition time");
        if (now.isBefore(previous)) throw new DomainValidationException("transition time must not move backwards");
        return now;
    }

    static void version(long value) {
        if (value < 0) throw new DomainValidationException("campaign version must not be negative");
    }

    static long nextVersion(long value) {
        if (value == Long.MAX_VALUE) throw new DomainValidationException("campaign version exhausted");
        return value + 1;
    }
}
