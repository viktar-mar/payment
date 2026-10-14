package dev.genericpayments.platform.provider.adapter.out.fake;
import dev.genericpayments.platform.catalog.domain.BillingInterval;
import java.time.*;
import java.util.Objects;
public final class FakeBillingCalendar {
    private FakeBillingCalendar() {}
    public static Instant periodEnd(Instant anchor,int anchorDay,int periods,BillingInterval interval) {
        Objects.requireNonNull(anchor);Objects.requireNonNull(interval);
        if(anchorDay<1||anchorDay>31||periods<1)throw new IllegalArgumentException("Invalid billing anchor");
        var base=anchor.atZone(ZoneOffset.UTC);
        var next=interval==BillingInterval.MONTH?base.plusMonths(periods):base.plusYears(periods);
        return next.withDayOfMonth(Math.min(anchorDay,next.toLocalDate().lengthOfMonth())).toInstant();
    }
}
