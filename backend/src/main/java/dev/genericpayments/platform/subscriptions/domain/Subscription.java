package dev.genericpayments.platform.subscriptions.domain;

import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Subscription(UUID id, SiteId siteId, CustomerId customerId,
                           ProductId productId, PaymentAccountId paymentAccountId,
                           SubscriptionPriceTerms billingPrice, SubscriptionPriceTerms entitledPrice,
                           Status billingStatus, String providerReference, Period currentPeriod,
                           Instant paidThrough, Period trial, Cancellation cancellation,
                           Change pendingChange, Debt debt, String terminalReason, long revision,
                           Instant createdAt, Instant updatedAt, Instant lastReconciledAt) {
    public enum Status { INCOMPLETE, TRIALING, ACTIVE, PAST_DUE, PAUSED, UNPAID, CANCELLED }
    public enum DisplayState { INCOMPLETE, TRIALING, ACTIVE, PAST_DUE, PAUSED, UNPAID, CANCELLED, CANCEL_AT_PERIOD_END }
    public enum ChangeState { PAYMENT_PENDING, SCHEDULED, UNKNOWN }

    public record Period(Instant start, Instant end) {
        public Period {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!start.isBefore(end)) throw new DomainValidationException("Period start must precede end");
        }
    }

    public record Cancellation(Instant effectiveAt) {
        public Cancellation { Objects.requireNonNull(effectiveAt, "effectiveAt"); }
    }

    public record Change(UUID operationId, SubscriptionPriceTerms target, Instant effectiveAt, ChangeState state) {
        public Change {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            Objects.requireNonNull(state, "state");
        }
    }

    public record Debt(UUID billingRecordId, Period unpaidPeriod, Instant graceDeadline) {
        public Debt {
            Objects.requireNonNull(billingRecordId, "billingRecordId");
            Objects.requireNonNull(unpaidPeriod, "unpaidPeriod");
            // An unpaid initial invoice has no previous paid boundary and no grace deadline.
        }
    }

    public Subscription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(paymentAccountId, "paymentAccountId");
        Objects.requireNonNull(billingPrice, "billingPrice");
        Objects.requireNonNull(billingStatus, "billingStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 0 || updatedAt.isBefore(createdAt)) {
            throw new DomainValidationException("Invalid subscription revision or timestamps");
        }
        validateTerms(productId, billingPrice, billingPrice);
        if (entitledPrice != null) validateTerms(productId, billingPrice, entitledPrice);
        if (pendingChange != null) validateTerms(productId, billingPrice, pendingChange.target());
        validateSafeReference(providerReference);
        validateSafeReference(terminalReason);
        if (billingStatus != Status.CANCELLED && terminalReason != null) {
            throw new DomainValidationException("Terminal reason requires confirmed cancellation");
        }
    }

    public boolean nonTerminal() { return billingStatus != Status.CANCELLED; }

    public String displayState(Instant now) {
        Objects.requireNonNull(now, "now");
        boolean live = billingStatus == Status.ACTIVE && paidThrough != null && now.isBefore(paidThrough)
            || billingStatus == Status.TRIALING && trial != null && now.isBefore(trial.end());
        return cancellation != null && live ? "CANCEL_AT_PERIOD_END" : billingStatus.name();
    }

    public Subscription confirmTrial(Period period, Instant at) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(at, "at");
        if (!nonTerminal() || paidThrough != null || trial != null) return this;
        if (billingStatus != Status.INCOMPLETE) throw conflict("Trial cannot start in this state");
        return project(billingPrice, billingPrice, Status.TRIALING, period, paidThrough, period,
            cancellation, pendingChange, debt, null, at);
    }
    /** Record authoritative original usage after a delayed paid or terminal observation. */
    public Subscription recordTrialHistory(Period period,Instant at){
        Objects.requireNonNull(period);Objects.requireNonNull(at);
        if(trial!=null){if(!trial.equals(period))throw conflict("Trial history conflicts");return this;}
        return project(billingPrice,entitledPrice,billingStatus,currentPeriod,paidThrough,period,cancellation,pendingChange,debt,terminalReason,at);
    }

    public Subscription confirmPaid(SubscriptionPriceTerms terms, Period period, Instant at) {
        return confirmPaid(terms, period, at, at);
    }

    public Subscription confirmPaid(SubscriptionPriceTerms terms, Period period, Instant billingAt, Instant recordedAt) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(billingAt, "billingAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (!nonTerminal()) return this;
        validateTerms(productId, billingPrice, terms);
        if (billingAt.isBefore(period.start())) return this;
        if (paidThrough != null && period.end().isBefore(paidThrough)) return this;
        boolean matchingChange = pendingChange != null && pendingChange.target().equals(terms);
        if (period.end().equals(paidThrough) && (!terms.equals(billingPrice) || !terms.equals(entitledPrice))
                && !matchingChange) return this;
        if (debt != null && (period.start().isAfter(debt.unpaidPeriod().start())
                || period.end().isBefore(debt.unpaidPeriod().end()))) return this;
        Change remaining = matchingChange ? null : pendingChange;
        if (billingStatus == Status.ACTIVE && terms.equals(billingPrice) && terms.equals(entitledPrice)
                && period.equals(currentPeriod) && period.end().equals(paidThrough) && debt == null
                && Objects.equals(remaining, pendingChange)) return this;
        return project(terms, terms, Status.ACTIVE, period, period.end(), trial, cancellation,
            remaining, null, null, recordedAt);
    }

    public Subscription renewalFailed(UUID billingRecordId, Period period, int graceDays, Instant at) {
        return renewalFailed(billingRecordId, period, graceDays, at, at);
    }

    public Subscription renewalFailed(UUID billingRecordId, Period period, int graceDays, Instant billingAt, Instant recordedAt) {
        Objects.requireNonNull(billingRecordId, "billingRecordId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(billingAt, "billingAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (graceDays < 0) throw new DomainValidationException("Grace days must not be negative");
        if (!nonTerminal()) return this;
        if (paidThrough != null && !period.end().isAfter(paidThrough)) return this;
        if (paidThrough != null && currentPeriod != null && currentPeriod.start().isBefore(paidThrough)
                && billingAt.isBefore(currentPeriod.start())) return this;
        if (debt != null) {
            if (debt.billingRecordId().equals(billingRecordId)) return this;
            throw conflict("An unpaid billing record already exists");
        }
        Instant deadline = paidThrough != null && entitledPrice != null
            ? paidThrough.plus(Duration.ofDays(graceDays)) : null;
        return project(billingPrice, entitledPrice, Status.PAST_DUE, period, paidThrough, trial,
            cancellation, pendingChange, new Debt(billingRecordId, period, deadline), null, recordedAt);
    }

    public Subscription pause(Instant at) {
        Objects.requireNonNull(at, "at");
        if (!nonTerminal() || billingStatus == Status.PAUSED) return this;
        return project(billingPrice, entitledPrice, Status.PAUSED, currentPeriod, paidThrough, trial,
            cancellation, pendingChange, debt, null, at);
    }

    public Subscription scheduleCancellation(Instant effectiveAt, Instant at) {
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(at, "at");
        if (!nonTerminal() || !at.isBefore(effectiveAt) || pendingChange != null) {
            throw conflict("Cancellation cannot be scheduled in this state");
        }
        if (cancellation != null && cancellation.effectiveAt().equals(effectiveAt)) return this;
        if (cancellation != null) throw conflict("A cancellation is already scheduled");
        return project(billingPrice, entitledPrice, billingStatus, currentPeriod, paidThrough, trial,
            new Cancellation(effectiveAt), pendingChange, debt, null, at);
    }

    public Subscription withdrawCancellation(Instant at) {
        Objects.requireNonNull(at, "at");
        if (!nonTerminal() || cancellation != null && !at.isBefore(cancellation.effectiveAt())) {
            throw conflict("Cancellation can no longer be withdrawn");
        }
        if (cancellation == null) return this;
        return project(billingPrice, entitledPrice, billingStatus, currentPeriod, paidThrough, trial,
            null, pendingChange, debt, null, at);
    }

    public Subscription cancel(String reason, Instant at) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(at, "at");
        validateSafeReference(reason);
        if (!nonTerminal()) return this;
        return project(billingPrice, entitledPrice, Status.CANCELLED, currentPeriod, paidThrough, trial,
            null, null, debt, reason, at);
    }

    private Subscription project(SubscriptionPriceTerms billing, SubscriptionPriceTerms entitled, Status status,
                                 Period period, Instant paid, Period trialPeriod, Cancellation cancel,
                                 Change change, Debt outstandingDebt, String reason, Instant at) {
        return new Subscription(id, siteId, customerId, productId, paymentAccountId, billing, entitled,
            status, providerReference, period, paid, trialPeriod, cancel, change, outstandingDebt,
            reason, Math.incrementExact(revision), createdAt, at, lastReconciledAt);
    }

    private static void validateTerms(ProductId productId, SubscriptionPriceTerms billing, SubscriptionPriceTerms terms) {
        if (!productId.equals(terms.productId())
                || !billing.regularAmount().currency().equals(terms.regularAmount().currency())) {
            throw new DomainValidationException("Subscription terms must share Product and currency");
        }
    }

    private static void validateSafeReference(String value) {
        if (value != null && (value.isBlank() || value.length() > 255 || value.codePoints().anyMatch(Character::isISOControl))) {
            throw new DomainValidationException("Invalid subscription reference");
        }
    }

    private static SubscriptionRuleViolation conflict(String message) {
        return new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.STATE_CONFLICT, message);
    }
}
