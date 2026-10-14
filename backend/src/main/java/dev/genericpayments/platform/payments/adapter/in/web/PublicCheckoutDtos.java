package dev.genericpayments.platform.payments.adapter.in.web;

import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.sites.domain.PaymentMethod;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class PublicCheckoutDtos {
    private PublicCheckoutDtos() {}
    public record CreateCheckoutRequest(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,62}") String priceKey) {}
    public record ConfirmCheckoutRequest(@NotNull PaymentMethod paymentMethodFamily) {}
    public record PriceSnapshotResponse(UUID priceId, String priceKey, int version, long amountMinor, String currency, String taxMode) {}
    public record CheckoutCreationResponse(UUID checkoutId, String checkoutToken, UUID paymentId, long amountMinor, String currency,
        PriceSnapshotResponse price, String providerType, String confirmationMode, Map<String, Object> providerClientPayload, String status, Instant expiresAt) {
        @Override public String toString() { return "CheckoutCreationResponse[checkoutId=" + checkoutId + ", token=redacted]"; }
    }
    public record CheckoutResponse(UUID checkoutId, UUID paymentId, long amountMinor, String currency, PriceSnapshotResponse price,
        String providerType, String confirmationMode, Map<String, Object> providerClientPayload, String status, Instant expiresAt) {}
    static PriceSnapshotResponse price(CheckoutView v) {
        var p = v.priceSnapshot();
        return p == null ? null : new PriceSnapshotResponse(p.priceId().value(), p.priceKey(), p.version(), p.money().minorUnits(), p.money().currency(), p.taxMode().name());
    }
    static CheckoutCreationResponse created(CheckoutCreation c) {
        var v = c.view(); return new CheckoutCreationResponse(v.checkoutId(), c.checkoutToken(), v.paymentId(), v.amountMinor(), v.currency(), price(v),
            v.providerType(), v.confirmationMode(), v.providerClientPayload(), v.status(), v.expiresAt());
    }
    static CheckoutResponse read(CheckoutView v) {
        return new CheckoutResponse(v.checkoutId(), v.paymentId(), v.amountMinor(), v.currency(), price(v), v.providerType(), v.confirmationMode(), v.providerClientPayload(), v.status(), v.expiresAt());
    }
}
