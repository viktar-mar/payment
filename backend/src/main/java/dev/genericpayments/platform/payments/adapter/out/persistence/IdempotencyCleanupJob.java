package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.port.out.IdempotencyPort;
import java.time.Clock;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class IdempotencyCleanupJob {
    private final IdempotencyPort records;
    private final Clock clock;
    // Disabled schedules must not force database infrastructure into lazy, database-free contexts.
    public IdempotencyCleanupJob(@Lazy IdempotencyPort records,Clock clock) { this.records=records; this.clock=clock; }
    @Scheduled(cron="${payment.idempotency.cleanup-cron:-}",zone="UTC")
    public int cleanup() { return records.cleanup(clock.instant()); }
}
