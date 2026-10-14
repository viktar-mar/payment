package dev.genericpayments.platform.donations.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.text.Normalizer;

/** Validated plain text for transient use before encryption. Never include this value in diagnostics. */
public record DonorMessage(String value) {
    public DonorMessage {
        DonationDomainRules.required(value, "donor message");
        value = Normalizer.normalize(value, Normalizer.Form.NFC);
        if (value.codePointCount(0, value.length()) > 500) {
            throw new DomainValidationException("donor message exceeds 500 Unicode code points");
        }
        if (value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
            || Character.getType(codePoint) == Character.FORMAT
            || Character.getType(codePoint) == Character.SURROGATE)) {
            throw new DomainValidationException("donor message contains forbidden Unicode characters");
        }
    }

    public static DonorMessage of(String value) {
        return new DonorMessage(value);
    }

    @Override
    public String toString() {
        return "DonorMessage[REDACTED]";
    }
}
