package dev.genericpayments.platform.customers.adapter.out.security;

import java.text.Normalizer;
import java.util.Locale;

public final class CustomerEmailNormalizer implements dev.genericpayments.platform.customers.application.port.out.EmailNormalizationPort {
    @Override
    public String normalize(String value) {
        if(value==null || value.length()>4096) throw invalid();
        String normalized=Normalizer.normalize(value,Normalizer.Form.NFC).trim().toLowerCase(Locale.ROOT);
        // Recompose after case mapping as well; this is profile normalization, never an identity merge rule.
        normalized=Normalizer.normalize(normalized,Normalizer.Form.NFC);
        int at=normalized.indexOf('@');
        if(normalized.length()>254 || at<1 || at!=normalized.lastIndexOf('@') || at==normalized.length()-1
            || normalized.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||c>=0xd800&&c<=0xdfff)) throw invalid();
        return normalized;
    }
    public String mask(String value) { normalize(value); return "***@***"; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid email input"); }
}
