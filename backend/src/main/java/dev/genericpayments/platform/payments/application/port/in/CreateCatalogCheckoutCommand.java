package dev.genericpayments.platform.payments.application.port.in;
import java.net.URI;
import java.util.UUID;
public record CreateCatalogCheckoutCommand(String siteKey, String priceKey, URI origin, UUID customerId,
                                           String idempotencyKey, String requestId) {
    public CreateCatalogCheckoutCommand(String siteKey, String priceKey, URI origin,
                                        String idempotencyKey, String requestId) {
        this(siteKey, priceKey, origin, null, idempotencyKey, requestId);
    }
}
