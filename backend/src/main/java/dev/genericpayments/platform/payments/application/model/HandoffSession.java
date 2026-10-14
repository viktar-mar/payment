package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.time.Instant;
import java.util.*;
public record HandoffSession(UUID id,PaymentId paymentId,SiteId siteId,String tokenHash,URI origin,Instant expiresAt,Instant createdAt,Instant consumedAt) {
    public HandoffSession {
        Objects.requireNonNull(id); Objects.requireNonNull(paymentId); Objects.requireNonNull(siteId); Objects.requireNonNull(origin);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(expiresAt);
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}") || !expiresAt.isAfter(createdAt)
            || consumedAt != null && consumedAt.isBefore(createdAt)) throw new IllegalArgumentException("Invalid handoff");
    }
    @Override public String toString() { return "HandoffSession[id="+id+", paymentId="+paymentId+"]"; }
}
