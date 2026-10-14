package dev.genericpayments.platform.customers.application.port.out;

import java.util.List;

public interface KeyedDigestPort {
    enum Purpose { EMAIL_LOOKUP, IDENTITY_SUBJECT, SDK_SESSION, OTP_CODE, SOURCE_IP, OTP_SITE_BUCKET, OTP_EMAIL_BUCKET,
        SITE_TOKEN_JTI, LINK_PROOF, HISTORY_CURSOR, OIDC_NONCE }
    DigestValue digest(Purpose purpose, String value);
    /** Returns one digest per retained key version, active first and then by version identifier. */
    List<DigestValue> candidates(Purpose purpose, String value);
    boolean matches(Purpose purpose, String value, DigestValue expected);
    record DigestValue(String value, String keyVersion) {
        @Override public String toString() { return "DigestValue[redacted]"; }
    }
}
