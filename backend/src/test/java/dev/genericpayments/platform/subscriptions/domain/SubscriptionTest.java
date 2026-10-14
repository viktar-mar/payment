package dev.genericpayments.platform.subscriptions.domain;

import static org.assertj.core.api.Assertions.*;
import static dev.genericpayments.platform.subscriptions.domain.SubscriptionFixtures.*;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SubscriptionTest {
    private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant END = Instant.parse("2026-10-31T10:00:00Z");

    @Test void duplicateRenewalFailureDoesNotExtendGrace() {
        var s = active(START, END);
        UUID invoice = UUID.randomUUID();
        var period = new Subscription.Period(END, Instant.parse("2026-11-30T10:00:00Z"));
        var failed = s.renewalFailed(invoice, period, 3, END.plusSeconds(1));
        var duplicate = failed.renewalFailed(invoice, period, 99, END.plusSeconds(80000));
        assertThat(duplicate.debt().graceDeadline()).isEqualTo(END.plusSeconds(259200));
        assertThat(duplicate.revision()).isEqualTo(failed.revision());
        assertThat(duplicate).isSameAs(failed);
        assertThat(failed.billingStatus()).isEqualTo(Subscription.Status.PAST_DUE);
        assertThat(failed.currentPeriod()).isEqualTo(period);
        assertThat(failed.paidThrough()).isEqualTo(END);
        assertThat(s.debt()).isNull();
    }

    @Test void confirmedPaymentRestoresExactTermsAndClearsDebt() {
        var s = active(START, END);
        var target = terms(s.productId(), "premium", 2000);
        var next = new Subscription.Period(END, END.plusSeconds(86400 * 30L));
        var failed = s.renewalFailed(UUID.randomUUID(), next, 3, END);
        var paid = failed.confirmPaid(target, next, END.plusSeconds(10));
        assertThat(paid.billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(paid.billingPrice()).isEqualTo(target);
        assertThat(paid.entitledPrice()).isEqualTo(target);
        assertThat(paid.paidThrough()).isEqualTo(next.end());
        assertThat(paid.debt()).isNull();
        assertThat(paid.revision()).isEqualTo(3);
        assertThat(paid.updatedAt()).isEqualTo(END.plusSeconds(10));
        assertThat(paid.createdAt()).isEqualTo(START);
    }

    @Test void optionalTrialPauseDoesNotCreateDebtOrPaidFact() {
        var s = active(START, END);
        s = facts(s, Subscription.Status.INCOMPLETE, s.billingPrice(), null, null, null, null, null, null, null);
        var trial = s.confirmTrial(new Subscription.Period(START, END), START);
        assertThat(trial.billingStatus()).isEqualTo(Subscription.Status.TRIALING);
        assertThat(trial.entitledPrice()).isEqualTo(trial.billingPrice());
        assertThat(trial.paidThrough()).isNull();
        var paused = trial.pause(END);
        assertThat(paused.billingStatus()).isEqualTo(Subscription.Status.PAUSED);
        assertThat(paused.debt()).isNull();
        assertThat(paused.nonTerminal()).isTrue();
        assertThat(new SubscriptionAccessPolicy().evaluate(paused, END).reason()).isEqualTo(AccessDecision.Reason.PAUSED);
    }

    @Test void lateTrialCannotReplacePaidEntitlementAndDuplicateTrialIsNoOp() {
        var s = active(START, END);
        assertThat(s.confirmTrial(s.currentPeriod(), START)).isSameAs(s);
        var incomplete = facts(s, Subscription.Status.INCOMPLETE, s.billingPrice(), null, null, null, null, null, null, null);
        var period = new Subscription.Period(START, END);
        var trial = incomplete.confirmTrial(period, START);
        assertThat(trial.confirmTrial(period, START.plusSeconds(1))).isSameAs(trial);
        var paused = trial.pause(END);
        assertThat(paused.confirmTrial(period, END.plusSeconds(1))).isSameAs(paused);
        var cancelled = incomplete.cancel("confirmed", START);
        assertThat(cancelled.confirmTrial(period, START)).isSameAs(cancelled);
    }

    @Test void historicalPaymentCannotEraseNewerDebtOrRegressEntitlement() {
        var s = active(START, END);
        var next = new Subscription.Period(END, END.plusSeconds(86400 * 30L));
        var failed = s.renewalFailed(UUID.randomUUID(), next, 3, END);
        assertThat(failed.confirmPaid(s.billingPrice(), s.currentPeriod(), END.plusSeconds(1))).isSameAs(failed);
        var recovered = failed.confirmPaid(s.billingPrice(), next, END.plusSeconds(2));
        assertThat(recovered.confirmPaid(s.billingPrice(), s.currentPeriod(), END.plusSeconds(3))).isSameAs(recovered);
        assertThatThrownBy(() -> failed.renewalFailed(UUID.randomUUID(), next, 3, END.plusSeconds(1)))
            .isInstanceOf(SubscriptionRuleViolation.class);
    }

    @Test void delayedFailureForAlreadyPaidPeriodCannotCreateDebtOrGrace() {
        var s = active(START, END);
        assertThat(s.renewalFailed(UUID.randomUUID(), s.currentPeriod(), 3, END.plusSeconds(1))).isSameAs(s);
        var next = new Subscription.Period(END, END.plusSeconds(86400 * 30L));
        var failed = s.renewalFailed(UUID.randomUUID(), next, 3, END);
        assertThat(failed.renewalFailed(UUID.randomUUID(), s.currentPeriod(), 3, END.plusSeconds(1))).isSameAs(failed);
    }

    @Test void confirmedCurrentPeriodProrationPromotesOnlyMatchingPendingTarget() {
        var s = active(START, END);
        var target = terms(s.productId(), "premium", 2000);
        var change = new Subscription.Change(UUID.randomUUID(), target, START.plusSeconds(10), Subscription.ChangeState.PAYMENT_PENDING);
        var pending = facts(s, s.billingStatus(), s.billingPrice(), s.entitledPrice(), s.currentPeriod(), END,
            null, null, change, null);
        var applied = pending.confirmPaid(target, s.currentPeriod(), START.plusSeconds(10));
        assertThat(applied.entitledPrice()).isEqualTo(target);
        assertThat(applied.pendingChange()).isNull();
        assertThat(applied.confirmPaid(target, s.currentPeriod(), START.plusSeconds(11))).isSameAs(applied);
    }

    @Test void delayedSamePeriodPaymentCannotReverseConfirmedProration() {
        var basic = active(START, END);
        var premium = terms(basic.productId(), "premium", 2000);
        var pending = facts(basic, basic.billingStatus(), basic.billingPrice(), basic.entitledPrice(),
            basic.currentPeriod(), END, null, null,
            new Subscription.Change(UUID.randomUUID(), premium, START.plusSeconds(10), Subscription.ChangeState.PAYMENT_PENDING), null);
        var upgraded = pending.confirmPaid(premium, basic.currentPeriod(), START.plusSeconds(10));
        var delayed = upgraded.confirmPaid(basic.billingPrice(), basic.currentPeriod(), START.plusSeconds(11));
        assertThat(delayed.billingPrice()).isEqualTo(premium);
        assertThat(delayed.entitledPrice()).isEqualTo(premium);
        assertThat(delayed.revision()).isEqualTo(upgraded.revision());
        assertThat(delayed).isSameAs(upgraded);
    }

    @Test void futurePeriodPaymentCannotPromoteEntitlementBeforeItsStart() {
        var s = active(START, END);
        var target = terms(s.productId(), "premium", 2000);
        var next = new Subscription.Period(END, END.plusSeconds(86400 * 30L));
        var pending = facts(s, s.billingStatus(), s.billingPrice(), s.entitledPrice(), s.currentPeriod(), END,
            null, null, new Subscription.Change(UUID.randomUUID(), target, END, Subscription.ChangeState.SCHEDULED), null);
        assertThat(pending.confirmPaid(target, next, END.minusSeconds(1))).isSameAs(pending);
        var applied = pending.confirmPaid(target, next, END);
        assertThat(applied.entitledPrice()).isEqualTo(target);
        assertThat(applied.currentPeriod()).isEqualTo(next);
        assertThat(applied.pendingChange()).isNull();
    }

    @Test void cancellationIsSeparateFromBillingFactsAndWithdrawalRequiresFutureTime() {
        var s = active(START, END).scheduleCancellation(END, START);
        assertThat(s.displayState(START)).isEqualTo("CANCEL_AT_PERIOD_END");
        var failed = s.renewalFailed(UUID.randomUUID(), new Subscription.Period(END, END.plusSeconds(86400)), 3, END);
        assertThat(failed.displayState(END)).isEqualTo("PAST_DUE");
        assertThat(s.withdrawCancellation(END.minusSeconds(1)).cancellation()).isNull();
        assertThatThrownBy(() -> s.withdrawCancellation(END)).isInstanceOf(SubscriptionRuleViolation.class)
            .extracting(e -> ((SubscriptionRuleViolation) e).reason()).isEqualTo(SubscriptionRuleViolation.Reason.STATE_CONFLICT);
    }

    @Test void immediateTerminalCancellationIgnoresLatePaymentAndPreservesDebt() {
        var next = new Subscription.Period(END, END.plusSeconds(86400));
        var failed = active(START, END).renewalFailed(UUID.randomUUID(), next, 3, END);
        var cancelled = failed.cancel("customer-requested", END);
        assertThat(cancelled.nonTerminal()).isFalse();
        assertThat(cancelled.terminalReason()).isEqualTo("customer-requested");
        assertThat(cancelled.debt()).isEqualTo(failed.debt());
        assertThat(cancelled.confirmPaid(cancelled.billingPrice(), next, END.plusSeconds(1))).isSameAs(cancelled);
        assertThat(new SubscriptionAccessPolicy().evaluate(active(START, END).cancel("confirmed", START), START).allowed()).isFalse();
    }

    @Test void periodsRequireStrictlyIncreasingInstantsAndGraceCannotBeNegative() {
        assertThatThrownBy(() -> new Subscription.Period(END, END)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Subscription.Period(END, START)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> active(START, END).renewalFailed(UUID.randomUUID(),
            new Subscription.Period(END, END.plusSeconds(1)), -1, END)).isInstanceOf(DomainValidationException.class);
    }

    @Test void termsAndProjectionRejectCrossProductAndCurrencyButAllowUnpaidCurrentPeriod() {
        var s = active(START, END);
        var foreign = terms(new ProductId(UUID.randomUUID()), "foreign", 1000);
        assertThatThrownBy(() -> s.confirmPaid(foreign, s.currentPeriod(), START)).isInstanceOf(DomainValidationException.class);
        var usd = new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()), "usd-plan", 1, s.productId(),
            Money.of(1000, "USD"), BillingInterval.MONTH, 0, TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL, TaxMode.NONE);
        assertThatThrownBy(() -> s.confirmPaid(usd, s.currentPeriod(), START)).isInstanceOf(DomainValidationException.class);
        var newer = new Subscription.Period(END, END.plusSeconds(86400));
        assertThat(facts(s, Subscription.Status.PAST_DUE, s.billingPrice(), s.entitledPrice(), newer, END,
            null, null, null, new Subscription.Debt(UUID.randomUUID(), newer, END.plusSeconds(259200))).paidThrough()).isEqualTo(END);
    }

    @Test void termsValidateKeyVersionAndTrialDays() {
        var s = active(START, END);
        for (int version : new int[] {0, -1}) {
            assertThatThrownBy(() -> new SubscriptionPriceTerms(s.billingPrice().priceId(), "basic", version,
                s.productId(), Money.of(1000, "EUR"), BillingInterval.MONTH, 0,
                TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL, TaxMode.NONE)).isInstanceOf(DomainValidationException.class);
        }
        assertThatThrownBy(() -> new SubscriptionPriceTerms(s.billingPrice().priceId(), "bad key", 1,
            s.productId(), Money.of(1000, "EUR"), BillingInterval.MONTH, 0,
            TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL, TaxMode.NONE)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new SubscriptionPriceTerms(s.billingPrice().priceId(), "basic", 1,
            s.productId(), Money.of(1000, "EUR"), BillingInterval.MONTH, -1,
            TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL, TaxMode.NONE)).isInstanceOf(DomainValidationException.class);
    }

    @Test void projectionRejectsMissingIdentityNegativeRevisionAndUnsafeReferences() {
        var s = active(START, END);
        assertThatThrownBy(() -> new Subscription(null, s.siteId(), s.customerId(), s.productId(), s.paymentAccountId(),
            s.billingPrice(), s.entitledPrice(), s.billingStatus(), null, s.currentPeriod(), END, null, null, null,
            null, null, 1, START, START, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Subscription(s.id(), s.siteId(), s.customerId(), s.productId(), s.paymentAccountId(),
            s.billingPrice(), s.entitledPrice(), s.billingStatus(), null, s.currentPeriod(), END, null, null, null,
            null, null, -1, START, START, null)).isInstanceOf(DomainValidationException.class);
        for (String reference : new String[] {"bad\nreference", "x".repeat(256), " "}) {
            assertThatThrownBy(() -> new Subscription(s.id(), s.siteId(), s.customerId(), s.productId(), s.paymentAccountId(),
                s.billingPrice(), s.entitledPrice(), s.billingStatus(), reference, s.currentPeriod(), END, null, null, null,
                null, null, 1, START, START, null)).isInstanceOf(DomainValidationException.class);
        }
        assertThatThrownBy(() -> s.cancel("bad\nreason", START)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Subscription.Change(null, s.billingPrice(), END, Subscription.ChangeState.UNKNOWN))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Subscription.Debt(null, s.currentPeriod(), END)).isInstanceOf(NullPointerException.class);
    }

    @Test void confirmedCancellationCanBeReconstructedWithoutProviderReason() {
        var s = active(START, END);
        var cancelled = new Subscription(s.id(), s.siteId(), s.customerId(), s.productId(), s.paymentAccountId(),
            s.billingPrice(), s.entitledPrice(), Subscription.Status.CANCELLED, s.providerReference(),
            s.currentPeriod(), END, null, null, null, null, null, 1, START, START, null);
        assertThat(cancelled.nonTerminal()).isFalse();
        assertThat(new SubscriptionAccessPolicy().evaluate(cancelled, START).reason()).isEqualTo(AccessDecision.Reason.CANCELLED);
        assertThatThrownBy(() -> s.cancel(null, START)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> s.cancel(" ", START)).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest @EnumSource(Subscription.Status.class)
    void onlyConfirmedCancelledStateReleasesProductSlot(Subscription.Status status) {
        var s = active(START, END);
        assertThat(facts(s, status, s.billingPrice(), s.entitledPrice(), s.currentPeriod(), END, null,
            null, null, null).nonTerminal()).isEqualTo(status != Subscription.Status.CANCELLED);
    }
}
