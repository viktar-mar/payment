package dev.genericpayments.platform.provider.adapter.out.stripe;

/** Explicit construction avoids configuration-binding diagnostics echoing rejected secrets. */
public final class StripeSandboxProperties {
    private final boolean enabled;
    private final StripeCredentials credentials;

    public StripeSandboxProperties(boolean enabled, String secretKey, String publishableKey, String webhookSecret) {
        this.enabled = enabled;
        this.credentials = enabled ? new StripeCredentials(secretKey, publishableKey, webhookSecret) : null;
    }

    public boolean enabled() { return enabled; }
    StripeCredentials credentials() { return credentials; }
    @Override public String toString() { return "StripeSandboxProperties[redacted]"; }
}
