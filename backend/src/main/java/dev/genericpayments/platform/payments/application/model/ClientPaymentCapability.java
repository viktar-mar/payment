package dev.genericpayments.platform.payments.application.model;
import java.util.Objects;
public final class ClientPaymentCapability {
    private final String publishableKey;
    private final String clientSecret;
    public ClientPaymentCapability(String publishableKey, String clientSecret) {
        this.publishableKey=Objects.requireNonNull(publishableKey); this.clientSecret=Objects.requireNonNull(clientSecret);
    }
    public String publishableKey() { return publishableKey; }
    public String clientSecret() { return clientSecret; }
    @Override public String toString() { return "ClientPaymentCapability[redacted]"; }
}
