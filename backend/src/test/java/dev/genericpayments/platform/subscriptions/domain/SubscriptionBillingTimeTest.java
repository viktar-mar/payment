package dev.genericpayments.platform.subscriptions.domain;

import static org.assertj.core.api.Assertions.*;
import static dev.genericpayments.platform.subscriptions.domain.SubscriptionFixtures.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubscriptionBillingTimeTest {
    private static final Instant UTC=Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant BILLING=UTC.plus(Duration.ofDays(35));

    @Test void futurePaidPeriodUsesBillingEligibilityAndOperationalAudit() {
        var base=active(UTC,UTC.plus(Duration.ofDays(30)));
        var period=new Subscription.Period(BILLING,BILLING.plus(Duration.ofDays(30)));
        var paid=base.confirmPaid(base.billingPrice(),period,BILLING,UTC);
        assertThat(paid.currentPeriod()).isEqualTo(period);
        assertThat(paid.updatedAt()).isEqualTo(UTC);
        assertThat(paid.createdAt()).isEqualTo(UTC);
    }
    @Test void renewalUsesBillingEligibilityButNeverAdvancesAuditOrGraceOnReplay() {
        var base=active(UTC,UTC.plus(Duration.ofDays(30)));
        var paid=base.confirmPaid(base.billingPrice(),new Subscription.Period(BILLING,BILLING.plus(Duration.ofDays(30))),BILLING,UTC);
        var period=new Subscription.Period(paid.paidThrough(),paid.paidThrough().plus(Duration.ofDays(30)));
        var bill=UUID.randomUUID();
        var failed=paid.renewalFailed(bill,period,3,period.start(),UTC);
        assertThat(failed.debt().graceDeadline()).isEqualTo(paid.paidThrough().plus(Duration.ofDays(3)));
        assertThat(failed.updatedAt()).isEqualTo(UTC);
        assertThat(failed.renewalFailed(bill,period,99,period.start().plusSeconds(1),UTC)).isSameAs(failed);
    }    @Test void distinctTimeRetainsBoundaryAndRecordedTimeInvariantsAndOldApiEquivalence() {
        var base=active(UTC,UTC.plus(Duration.ofDays(30)));
        var period=new Subscription.Period(BILLING,BILLING.plus(Duration.ofDays(30)));
        assertThat(base.confirmPaid(base.billingPrice(),period,BILLING.minusSeconds(1),UTC)).isSameAs(base);
        assertThatThrownBy(()->base.confirmPaid(base.billingPrice(),period,BILLING,UTC.minusSeconds(1)))
            .isInstanceOf(dev.genericpayments.platform.shared.domain.DomainValidationException.class);
        assertThat(base.confirmPaid(base.billingPrice(),period,BILLING)).isEqualTo(base.confirmPaid(base.billingPrice(),period,BILLING,BILLING));
        var next=new Subscription.Period(base.paidThrough(),base.paidThrough().plus(Duration.ofDays(30)));var bill=UUID.randomUUID();
        assertThat(base.renewalFailed(bill,next,3,BILLING)).isEqualTo(base.renewalFailed(bill,next,3,BILLING,BILLING));
    }
}
