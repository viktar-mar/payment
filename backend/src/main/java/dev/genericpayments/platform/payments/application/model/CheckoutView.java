package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.payments.domain.PriceSnapshot;
import java.time.Instant;
import java.util.*;
public record CheckoutView(UUID checkoutId, UUID paymentId, long amountMinor, String currency, PriceSnapshot priceSnapshot,
    String providerType, String confirmationMode, Map<String, Object> providerClientPayload, String status, Instant expiresAt) {
    public CheckoutView { providerClientPayload = Map.copyOf(providerClientPayload); }
}
