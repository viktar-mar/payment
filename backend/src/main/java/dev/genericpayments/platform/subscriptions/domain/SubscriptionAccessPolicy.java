package dev.genericpayments.platform.subscriptions.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class SubscriptionAccessPolicy {
    public AccessDecision evaluate(Subscription subscription, Instant now) {
        Objects.requireNonNull(subscription, "subscription");
        Objects.requireNonNull(now, "now");
        var status = subscription.billingStatus();
        if (!subscription.nonTerminal()) return decision(subscription, now, AccessDecision.Reason.CANCELLED, null);
        if (status == Subscription.Status.PAUSED) return decision(subscription, now, AccessDecision.Reason.PAUSED, null);
        if (status == Subscription.Status.UNPAID) return decision(subscription, now, AccessDecision.Reason.UNPAID, null);
        if (status == Subscription.Status.INCOMPLETE) return decision(subscription, now, AccessDecision.Reason.INITIAL_PAYMENT_REQUIRED, null);
        if (subscription.cancellation() != null && !now.isBefore(subscription.cancellation().effectiveAt())) {
            return decision(subscription, now, AccessDecision.Reason.CANCELLED, null);
        }
        if (subscription.entitledPrice() == null) return decision(subscription, now, AccessDecision.Reason.INITIAL_PAYMENT_REQUIRED, null);
        if (status == Subscription.Status.TRIALING && subscription.trial() != null
                && !now.isBefore(subscription.trial().start()) && now.isBefore(subscription.trial().end())) {
            return decision(subscription, now, AccessDecision.Reason.TRIAL, subscription.trial().end());
        }
        boolean paidPeriodStarted = status == Subscription.Status.PAST_DUE
            ? !now.isBefore(subscription.createdAt())
            : subscription.currentPeriod() != null && !now.isBefore(subscription.currentPeriod().start());
        if ((status == Subscription.Status.ACTIVE || status == Subscription.Status.PAST_DUE)
                && subscription.paidThrough() != null && paidPeriodStarted
                && now.isBefore(subscription.paidThrough())) {
            return decision(subscription, now, AccessDecision.Reason.PAID, subscription.paidThrough());
        }
        if (status == Subscription.Status.PAST_DUE && subscription.paidThrough() == null) {
            return decision(subscription, now, AccessDecision.Reason.INITIAL_PAYMENT_REQUIRED, null);
        }
        if (status == Subscription.Status.PAST_DUE && subscription.paidThrough() != null
                && subscription.debt() != null && subscription.debt().graceDeadline() != null
                && !now.isBefore(subscription.paidThrough()) && now.isBefore(subscription.debt().graceDeadline())) {
            return decision(subscription, now, AccessDecision.Reason.GRACE, subscription.debt().graceDeadline());
        }
        return decision(subscription, now, AccessDecision.Reason.EXPIRED, null);
    }

    public static Instant validUntil(Instant now, Instant boundary) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(boundary, "boundary");
        Instant cap = now.plus(Duration.ofMinutes(5));
        return boundary.isBefore(cap) ? boundary : cap;
    }

    private AccessDecision decision(Subscription subscription, Instant now, AccessDecision.Reason reason, Instant boundary) {
        var terms = subscription.entitledPrice();
        if (boundary != null && subscription.cancellation() != null
                && subscription.cancellation().effectiveAt().isBefore(boundary)) {
            boundary = subscription.cancellation().effectiveAt();
        }
        return new AccessDecision(boundary != null, reason, subscription.siteId(), subscription.customerId(),
            subscription.productId(), subscription.id(), terms == null ? null : terms.priceId(),
            terms == null ? null : terms.version(), subscription.revision(), now,
            boundary == null ? null : validUntil(now, boundary));
    }
}
