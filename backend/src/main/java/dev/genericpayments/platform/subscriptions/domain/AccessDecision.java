package dev.genericpayments.platform.subscriptions.domain;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccessDecision(boolean allowed, Reason reason, SiteId siteId,
                             CustomerId customerId, ProductId productId, UUID subscriptionId,
                             PriceId entitledPriceId, Integer entitledPriceVersion, long revision,
                             Instant evaluatedAt, Instant validUntil) {
    public enum Reason { PAID, TRIAL, GRACE, INITIAL_PAYMENT_REQUIRED,
        PAUSED, UNPAID, CANCELLED, EXPIRED, NO_SUBSCRIPTION }

    public AccessDecision {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        boolean accessReason = reason == Reason.PAID || reason == Reason.TRIAL || reason == Reason.GRACE;
        if (revision < 0 || allowed != accessReason
                || (entitledPriceId == null) != (entitledPriceVersion == null)
                || entitledPriceVersion != null && entitledPriceVersion <= 0
                || reason == Reason.NO_SUBSCRIPTION && (subscriptionId != null || entitledPriceId != null)
                || reason != Reason.NO_SUBSCRIPTION && subscriptionId == null
                || allowed && (entitledPriceId == null || validUntil == null || !evaluatedAt.isBefore(validUntil)
                    || validUntil.isAfter(evaluatedAt.plusSeconds(300)))
                || !allowed && validUntil != null) {
            throw new DomainValidationException("Invalid access decision");
        }
    }
}
