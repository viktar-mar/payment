package dev.genericpayments.platform.payments.application.model;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
public final class ClientPaymentSession {
    private final UUID checkoutId, paymentId, attemptId;
    private final ClientPaymentCapability capability;
    private final Instant expiresAt;
    private final URI returnUrl;
    public ClientPaymentSession(UUID checkoutId, UUID paymentId, UUID attemptId, ClientPaymentCapability capability, Instant expiresAt, URI returnUrl) {
        this.checkoutId=checkoutId;this.paymentId=paymentId;this.attemptId=attemptId;this.capability=capability;this.expiresAt=expiresAt;this.returnUrl=returnUrl;
    }
    public UUID checkoutId() { return checkoutId; }
    public UUID paymentId() { return paymentId; }
    public UUID attemptId() { return attemptId; }
    public ClientPaymentCapability capability() { return capability; }
    public Instant expiresAt() { return expiresAt; }
    public URI returnUrl() { return returnUrl; }
    @Override public String toString() { return "ClientPaymentSession[redacted]"; }
}
