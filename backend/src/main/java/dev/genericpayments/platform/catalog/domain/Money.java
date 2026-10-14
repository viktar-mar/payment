package dev.genericpayments.platform.catalog.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Currency;
import java.util.Locale;

public record Money(long minorUnits, String currency) {
    public Money {
        if (minorUnits <= 0) {
            throw new DomainValidationException("money minor units must be positive");
        }
        try {
            currency = Currency.getInstance(currency.toUpperCase(Locale.ROOT)).getCurrencyCode();
        } catch (RuntimeException exception) {
            throw new DomainValidationException("invalid currency");
        }
    }

    public static Money of(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }
}
