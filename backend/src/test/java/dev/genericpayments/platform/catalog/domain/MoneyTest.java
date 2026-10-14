package dev.genericpayments.platform.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void normalizesCurrencyToUppercaseIsoCode() {
        Money money = Money.of(1999, "eur");

        assertThat(money.minorUnits()).isEqualTo(1999);
        assertThat(money.currency()).isEqualTo("EUR");
    }

    @Test
    void rejectsZeroAndNegativeMinorUnits() {
        assertThatThrownBy(() -> Money.of(0, "EUR"))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Money.of(-1, "EUR"))
            .isInstanceOf(DomainValidationException.class);
    }
}
