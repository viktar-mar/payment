package dev.genericpayments.platform.payments.application.model;
import java.net.URI;
import java.util.UUID;
public record PrepareClientPaymentCommand(UUID checkoutId, URI origin, String checkoutToken, String idempotencyKey, String requestId) {
    @Override public String toString() { return "PrepareClientPaymentCommand[redacted]"; }
}
