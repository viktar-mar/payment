package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.time.Instant;
import java.util.*;
public record CheckoutSession(UUID id, PaymentId paymentId, SiteId siteId, String tokenHash, URI origin, Set<String> scopes, Instant expiresAt, Instant createdAt) {
    public CheckoutSession {
        Objects.requireNonNull(id); Objects.requireNonNull(paymentId); Objects.requireNonNull(siteId); Objects.requireNonNull(origin);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(expiresAt); scopes = Set.copyOf(scopes);
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}") || !expiresAt.isAfter(createdAt)
            || scopes.isEmpty() || !Set.of("read", "confirm").containsAll(scopes)) throw new IllegalArgumentException("Invalid checkout session");
    }
    @Override public String toString() { return "CheckoutSession[id=" + id + ", paymentId=" + paymentId + "]"; }
}
