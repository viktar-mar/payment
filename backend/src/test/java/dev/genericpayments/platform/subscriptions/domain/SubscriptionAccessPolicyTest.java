package dev.genericpayments.platform.subscriptions.domain;

import static org.assertj.core.api.Assertions.*;
import static dev.genericpayments.platform.subscriptions.domain.SubscriptionFixtures.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SubscriptionAccessPolicyTest {
    private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant END = Instant.parse("2026-10-31T10:00:00Z");
    private final SubscriptionAccessPolicy policy = new SubscriptionAccessPolicy();

    @Test void paidAccessExpiresWithoutAJob() {
        Instant start = Instant.parse("2028-02-29T12:00:00Z");
        Instant end = Instant.parse("2029-02-28T12:00:00Z");
        var s = active(start, end);
        assertThat(policy.evaluate(s, end.minusSeconds(1)).validUntil()).isEqualTo(end);
        assertThat(policy.evaluate(s, end).allowed()).isFalse();
        assertThat(policy.evaluate(s, start).validUntil()).isEqualTo(start.plusSeconds(300));
    }

    @Test void reconstructedFuturePaidPeriodStartsOnlyAtConfirmedStart() {
        var s = active(START, END);
        var next = new Subscription.Period(END, END.plusSeconds(86400 * 30L));
        var future = facts(s, Subscription.Status.ACTIVE, s.billingPrice(), s.entitledPrice(), next,
            next.end(), null, null, null, null);
        assertThat(policy.evaluate(future, END.minusSeconds(1)).allowed()).isFalse();
        assertThat(policy.evaluate(future, END).reason()).isEqualTo(AccessDecision.Reason.PAID);
    }

    @Test void earlyRenewalFailureCannotOpenFutureConfirmedPaidPeriod() {
        var s = active(START, END);
        var paid = new Subscription.Period(END, Instant.parse("2026-11-30T10:00:00Z"));
        var future = facts(s, Subscription.Status.ACTIVE, s.billingPrice(), s.entitledPrice(), paid,
            paid.end(), null, null, null, null);
        var now = Instant.parse("2026-10-30T10:00:00Z");
        assertThat(policy.evaluate(future, now).allowed()).isFalse();
        var unpaid = new Subscription.Period(paid.end(), Instant.parse("2026-12-31T10:00:00Z"));
        var observed = future.renewalFailed(UUID.randomUUID(), unpaid, 3, now);
        assertThat(policy.evaluate(observed, now).allowed()).isFalse();
        assertThat(observed).isSameAs(future);
        assertThat(observed.revision()).isEqualTo(future.revision());
        assertThat(policy.evaluate(observed, paid.start()).reason()).isEqualTo(AccessDecision.Reason.PAID);
    }

    @Test void pastDuePreservesPreviousPaidAccessBeforeUnpaidPeriodStarts() {
        var s = active(START, END);
        var next = new Subscription.Period(END, END.plusSeconds(86400 * 30L));
        var failed = s.renewalFailed(UUID.randomUUID(), next, 3, END.minusSeconds(100));
        var decision = policy.evaluate(failed, END.minusSeconds(1));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(decision.validUntil()).isEqualTo(END);
    }

    @ParameterizedTest
    @CsvSource({"-1,true,PAID", "0,false,EXPIRED", "1,false,EXPIRED"})
    void paidBoundaryIsExclusive(long offset, boolean allowed, AccessDecision.Reason reason) {
        var result = policy.evaluate(active(START, END), END.plusSeconds(offset));
        assertThat(result.allowed()).isEqualTo(allowed);
        assertThat(result.reason()).isEqualTo(reason);
        if (!allowed) assertThat(result.validUntil()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"-1,true,TRIAL", "0,false,EXPIRED", "1,false,EXPIRED"})
    void trialBoundaryIsExclusive(long offset, boolean allowed, AccessDecision.Reason reason) {
        var s = incomplete().confirmTrial(new Subscription.Period(START, END), START);
        var result = policy.evaluate(s, END.plusSeconds(offset));
        assertThat(result.allowed()).isEqualTo(allowed);
        assertThat(result.reason()).isEqualTo(reason);
        if (allowed) assertThat(result.validUntil()).isEqualTo(END);
    }

    @ParameterizedTest
    @CsvSource({"-1,true,GRACE", "0,false,EXPIRED", "1,false,EXPIRED"})
    void graceBoundaryIsExclusive(long offset, boolean allowed, AccessDecision.Reason reason) {
        var failed = active(START, END).renewalFailed(UUID.randomUUID(),
            new Subscription.Period(END, END.plusSeconds(86400 * 30L)), 3, END);
        var result = policy.evaluate(failed, END.plusSeconds(259200 + offset));
        assertThat(result.allowed()).isEqualTo(allowed);
        assertThat(result.reason()).isEqualTo(reason);
        if (allowed) assertThat(result.validUntil()).isEqualTo(END.plusSeconds(259200));
    }

    @Test void graceZeroAndLateFirstFailureNeverExtendPreviousPaidBoundary() {
        var period = new Subscription.Period(END, END.plusSeconds(86400 * 30L));
        var zero = active(START, END).renewalFailed(UUID.randomUUID(), period, 0, END);
        assertThat(policy.evaluate(zero, END).allowed()).isFalse();
        var late = active(START, END).renewalFailed(UUID.randomUUID(), period, 3, END.plusSeconds(300000));
        assertThat(late.debt().graceDeadline()).isEqualTo(END.plusSeconds(259200));
        assertThat(policy.evaluate(late, END.plusSeconds(300000)).allowed()).isFalse();
    }

    @Test void initialPaymentAndExpiredTrialCannotAcquireGrace() {
        var period = new Subscription.Period(END, END.plusSeconds(86400 * 30L));
        for (var s : new Subscription[] {incomplete(), incomplete().confirmTrial(new Subscription.Period(START, END), START)}) {
            var failed = s.renewalFailed(UUID.randomUUID(), period, 3, END);
            assertThat(policy.evaluate(failed, END).reason()).isEqualTo(AccessDecision.Reason.INITIAL_PAYMENT_REQUIRED);
            assertThat(policy.evaluate(failed, END).allowed()).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({"INCOMPLETE,INITIAL_PAYMENT_REQUIRED", "PAUSED,PAUSED", "UNPAID,UNPAID", "CANCELLED,CANCELLED"})
    void denyingStatesOverrideHistoricalPaidFacts(Subscription.Status status, AccessDecision.Reason reason) {
        var s = active(START, END);
        s = facts(s, status, s.billingPrice(), s.entitledPrice(), s.currentPeriod(), s.paidThrough(), null, null, null, null);
        assertThat(policy.evaluate(s, START).allowed()).isFalse();
        assertThat(policy.evaluate(s, START).reason()).isEqualTo(reason);
    }

    @Test void cancellationCapsBothPaidAccessAndCacheAndDeniesAtEffectiveTime() {
        var s = active(START, END).scheduleCancellation(START.plusSeconds(100), START);
        assertThat(policy.evaluate(s, START).validUntil()).isEqualTo(START.plusSeconds(100));
        assertThat(policy.evaluate(s, START.plusSeconds(100)).allowed()).isFalse();
        assertThat(policy.evaluate(s, START.plusSeconds(100)).reason()).isEqualTo(AccessDecision.Reason.CANCELLED);
    }

    @Test void unpaidBillingTargetDoesNotReplaceGraceEntitlement() {
        var s = active(START, END);
        var target = terms(s.productId(), "premium", 2000);
        var period = new Subscription.Period(END, END.plusSeconds(86400 * 30L));
        s = facts(s, Subscription.Status.ACTIVE, target, s.entitledPrice(), period, END, null, null,
            new Subscription.Change(UUID.randomUUID(), target, END, Subscription.ChangeState.SCHEDULED), null);
        var result = policy.evaluate(s.renewalFailed(UUID.randomUUID(), period, 3, END), END.plusSeconds(1));
        assertThat(result.reason()).isEqualTo(AccessDecision.Reason.GRACE);
        assertThat(result.entitledPriceId()).isEqualTo(s.entitledPrice().priceId()).isNotEqualTo(target.priceId());
        assertThat(result.entitledPriceVersion()).isEqualTo(1);
        assertThat(result.siteId()).isEqualTo(s.siteId());
        assertThat(result.customerId()).isEqualTo(s.customerId());
        assertThat(result.productId()).isEqualTo(s.productId());
        assertThat(result.subscriptionId()).isEqualTo(s.id());
        assertThat(result.revision()).isEqualTo(s.revision() + 1);
        assertThat(result.evaluatedAt()).isEqualTo(END.plusSeconds(1));
        assertThat(result.validUntil()).isEqualTo(END.plusSeconds(301));
    }

    @Test void allowedDecisionRequiresExactTermsAndFutureExpiry() {
        var s = active(START, END);
        assertThatThrownBy(() -> new AccessDecision(true, AccessDecision.Reason.PAID, s.siteId(), s.customerId(),
            s.productId(), s.id(), null, null, 1, START, START.plusSeconds(1))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new AccessDecision(true, AccessDecision.Reason.PAID, s.siteId(), s.customerId(),
            s.productId(), s.id(), s.entitledPrice().priceId(), 1, 1, START, START)).isInstanceOf(RuntimeException.class);
        var none = new AccessDecision(false, AccessDecision.Reason.NO_SUBSCRIPTION, s.siteId(), s.customerId(),
            s.productId(), null, null, null, 0, START, null);
        assertThat(none.allowed()).isFalse();
    }

    private Subscription incomplete() {
        var s = active(START, END);
        return facts(s, Subscription.Status.INCOMPLETE, s.billingPrice(), null, null, null, null, null, null, null);
    }
}
