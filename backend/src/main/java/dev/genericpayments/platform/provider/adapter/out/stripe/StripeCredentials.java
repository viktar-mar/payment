package dev.genericpayments.platform.provider.adapter.out.stripe;

import java.util.Locale;

/** Credentials stay in adapter memory and must never enter diagnostics or persistence. */
public final class StripeCredentials {
    private final String secretKey;
    private final String publishableKey;
    private final String webhookSecret;

    public StripeCredentials(String secretKey, String publishableKey, String webhookSecret) {
        this.secretKey = requireKey(secretKey, "(?:sk|rk)_test_[A-Za-z0-9]+", "secret key");
        this.publishableKey = requireKey(publishableKey, "pk_test_[A-Za-z0-9]+", "publishable key");
        this.webhookSecret = requireKey(webhookSecret, "whsec_[A-Za-z0-9]+", "webhook secret");
    }

    public String secretKey() { return secretKey; }
    public String publishableKey() { return publishableKey; }
    public String webhookSecret() { return webhookSecret; }

    private static String requireKey(String value, String pattern, String field) {
        if (value == null || !value.matches(pattern)
                || value.toLowerCase(Locale.ROOT).contains("replace_me")
                || value.toLowerCase(Locale.ROOT).contains("your_key")) {
            throw new IllegalArgumentException("Stripe Sandbox " + field + " is missing or invalid");
        }
        return value;
    }

    @Override public String toString() { return "StripeCredentials[redacted]"; }
}
