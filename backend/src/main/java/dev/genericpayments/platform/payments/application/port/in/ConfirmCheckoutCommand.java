package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.sites.domain.PaymentMethod;
import java.net.URI;
import java.util.UUID;
public record ConfirmCheckoutCommand(UUID checkoutId, URI origin, String checkoutToken, PaymentMethod method, String idempotencyKey, String requestId) {
    @Override public String toString() { return "ConfirmCheckoutCommand[checkoutId=" + checkoutId + ", token=redacted]"; }
}
