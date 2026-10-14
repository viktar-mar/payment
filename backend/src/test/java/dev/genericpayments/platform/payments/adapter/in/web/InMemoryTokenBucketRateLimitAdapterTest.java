package dev.genericpayments.platform.payments.adapter.in.web;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.port.out.RateLimitPort;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class InMemoryTokenBucketRateLimitAdapterTest {
    @Test void capacityRefillsAtDeterministicIntervalAndKeysStayBounded() {
        var clock = new dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock();
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(Instant.parse("2026-10-04T12:00:00Z"));
        var limiter = new InMemoryTokenBucketRateLimitAdapter(clock, 2, 3, 4, 2);
        var key = new RateLimitPort.Key(UUID.randomUUID(), "127.0.0.1", RateLimitPort.Operation.CREATE);
        assertThat(limiter.acquire(key).allowed()).isTrue();
        assertThat(limiter.acquire(key).allowed()).isTrue();
        assertThat(limiter.acquire(key).retryAfterSeconds()).isEqualTo(30);
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(Instant.parse("2026-10-04T12:00:29Z"));
        assertThat(limiter.acquire(key).retryAfterSeconds()).isEqualTo(1);
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(Instant.parse("2026-10-04T12:00:30Z"));
        assertThat(limiter.acquire(key).allowed()).isTrue();
        for (int i = 0; i < 100; i++) limiter.acquire(new RateLimitPort.Key(key.siteId(), "ip-" + i, RateLimitPort.Operation.CREATE));
        assertThat(limiter.keyCount()).isEqualTo(2);
        assertThat(limiter.acquire(key).allowed()).isTrue();
    }
    @Test void operationLimitsAreIndependentAndBackwardClockDoesNotMintTokens() {
        var clock = new dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock();
        var now = Instant.parse("2026-10-04T12:00:00Z");
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(now);
        var limiter = new InMemoryTokenBucketRateLimitAdapter(clock, 1, 2, 3, 10);
        var site = UUID.randomUUID();
        for (var operation : RateLimitPort.Operation.values()) {
            int capacity = operation == RateLimitPort.Operation.CREATE ? 1 : operation == RateLimitPort.Operation.READ ? 3 : 2;
            var key = new RateLimitPort.Key(site, "ip", operation);
            for (int i = 0; i < capacity; i++) assertThat(limiter.acquire(key).allowed()).isTrue();
            assertThat(limiter.acquire(key).allowed()).isFalse();
        }
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(now.minusSeconds(60));
        assertThat(limiter.acquire(new RateLimitPort.Key(site, "ip", RateLimitPort.Operation.CREATE)).allowed()).isFalse();
    }
}
