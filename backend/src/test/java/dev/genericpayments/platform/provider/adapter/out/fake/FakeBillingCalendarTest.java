package dev.genericpayments.platform.provider.adapter.out.fake;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.BillingInterval;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class FakeBillingCalendarTest {
    @Test void monthlyAnchorReturnsToTheThirtyFirstAfterFebruary() {
        Instant anchor = Instant.parse("2028-01-31T10:00:00Z");
        assertThat(FakeBillingCalendar.periodEnd(anchor,31,1,BillingInterval.MONTH)).isEqualTo(Instant.parse("2028-02-29T10:00:00Z"));
        assertThat(FakeBillingCalendar.periodEnd(anchor,31,2,BillingInterval.MONTH)).isEqualTo(Instant.parse("2028-03-31T10:00:00Z"));
    }
    @Test void leapDayReturnsAfterNonLeapYears() {
        var anchor=Instant.parse("2028-02-29T10:00:00Z");
        assertThat(FakeBillingCalendar.periodEnd(anchor,29,1,BillingInterval.YEAR)).isEqualTo(Instant.parse("2029-02-28T10:00:00Z"));
        assertThat(FakeBillingCalendar.periodEnd(anchor,29,4,BillingInterval.YEAR)).isEqualTo(Instant.parse("2032-02-29T10:00:00Z"));
    }
    @Test void lisbonDstNeverChangesUtcBoundaries() {
        var original=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"));
            assertThat(FakeBillingCalendar.periodEnd(Instant.parse("2028-02-26T01:30:00Z"),26,1,BillingInterval.MONTH)).isEqualTo(Instant.parse("2028-03-26T01:30:00Z"));
            assertThat(FakeBillingCalendar.periodEnd(Instant.parse("2028-09-29T01:30:00Z"),29,1,BillingInterval.MONTH)).isEqualTo(Instant.parse("2028-10-29T01:30:00Z"));
        } finally {TimeZone.setDefault(original);}
    }
    @Test void rejectsInvalidAnchors() {
        assertThatIllegalArgumentException().isThrownBy(()->FakeBillingCalendar.periodEnd(Instant.EPOCH,0,1,BillingInterval.MONTH));
        assertThatIllegalArgumentException().isThrownBy(()->FakeBillingCalendar.periodEnd(Instant.EPOCH,31,0,BillingInterval.YEAR));
    }
}
