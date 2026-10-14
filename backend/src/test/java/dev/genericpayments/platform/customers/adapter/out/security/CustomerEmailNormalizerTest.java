package dev.genericpayments.platform.customers.adapter.out.security;

import static org.assertj.core.api.Assertions.*;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerEmailNormalizerTest {
    private final CustomerEmailNormalizer normalizer = new CustomerEmailNormalizer();
    @Test void normalizesNfcWhitespaceAndRootCaseRegardlessOfDefaultLocale() {
        var before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(normalizer.normalize("  I\u0301NFO@EXAMPLE.TEST  ").equals("ínfo@example.test")).isTrue();
        } finally { Locale.setDefault(before); }
    }
    @Test void maskingRevealsNoEmailCharacters() {
        assertThat(normalizer.mask("fixture@example.test")).isEqualTo("***@***");
    }
    @ParameterizedTest(name="invalid-email-{index}") @NullAndEmptySource @ValueSource(strings={" ","bad","a@@example.test","@example.test","a@","a\nb@example.test","a b@example.test","\ud800@example.test","a\u00a0b@example.test"})
    void rejectsUnsafeInputsWithSafeDiagnostics(String value) {
        assertThatThrownBy(() -> normalizer.normalize(value)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid email input").hasNoCause();
    }
    @Test void boundsRawAndNormalizedLength() {
        assertThatThrownBy(() -> normalizer.normalize("a".repeat(255)+"@example.test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalize(" ".repeat(4097)+"a@b.test")).isInstanceOf(IllegalArgumentException.class);
    }
}
