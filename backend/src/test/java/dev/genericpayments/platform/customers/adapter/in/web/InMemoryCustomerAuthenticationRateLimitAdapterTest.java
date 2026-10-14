package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.application.port.out.PublicCustomerAuthenticationRateLimitPort;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InMemoryCustomerAuthenticationRateLimitAdapterTest {
    @Test
    void siteAndOperationBucketsAreIndependentRefillDeterministicallyAndStayBounded() {
        var clock=new TestClock();
        TestClock.set(Instant.parse("2026-10-04T12:00:00Z"));
        var limiter=new InMemoryCustomerAuthenticationRateLimitAdapter(clock,2,3,2,5,2);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        var firstSiteExchange=key(first,PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_EXCHANGE);
        assertThat(limiter.acquire(firstSiteExchange).allowed()).isTrue();
        assertThat(limiter.acquire(firstSiteExchange).allowed()).isTrue();
        assertThat(limiter.acquire(firstSiteExchange).retryAfterSeconds()).isEqualTo(30);
        TestClock.set(Instant.parse("2026-10-04T12:00:30Z"));
        assertThat(limiter.acquire(firstSiteExchange).allowed()).isTrue();
        TestClock.set(Instant.parse("2026-10-04T11:59:30Z"));
        assertThat(limiter.acquire(firstSiteExchange).allowed()).isFalse();
        assertThat(limiter.acquire(key(second,PublicCustomerAuthenticationRateLimitPort.Operation.SITE_TOKEN_EXCHANGE)).allowed()).isTrue();
        assertThat(limiter.acquire(key(first,PublicCustomerAuthenticationRateLimitPort.Operation.OIDC_BEGIN)).allowed()).isTrue();
        assertThat(limiter.keyCount()).isEqualTo(2);
    }

    @Test
    void concurrentAcquireCannotOversubscribeCapacity() throws Exception {
        var clock=new TestClock();
        TestClock.set(Instant.parse("2026-10-04T12:00:00Z"));
        var limiter=new InMemoryCustomerAuthenticationRateLimitAdapter(clock,10,10,10,10,100);
        var key=key(UUID.randomUUID(),PublicCustomerAuthenticationRateLimitPort.Operation.OIDC_EXCHANGE);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            List<Future<Boolean>> calls=new ArrayList<>();
            for(int index=0;index<100;index++) calls.add(executor.submit(() -> { start.await(); return limiter.acquire(key).allowed(); }));
            start.countDown();
            int allowed=0;
            for(Future<Boolean> call:calls) if(call.get(5,TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(10);
        }
    }

    @ParameterizedTest(name="rejects constructor values {0}")
    @MethodSource("outOfRangeConstructorValues")
    void rejectsUnsafeLowAndHighValuesWithoutClamping(int[] values) {
        assertThatThrownBy(() -> new InMemoryCustomerAuthenticationRateLimitAdapter(
            java.time.Clock.systemUTC(),values[0],values[1],values[2],values[3],values[4]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static List<int[]> outOfRangeConstructorValues() {
        return List.of(
            new int[]{0,30,30,20,10_000},new int[]{-1,30,30,20,10_000},new int[]{10_001,30,30,20,10_000},
            new int[]{20,0,30,20,10_000},new int[]{20,-1,30,20,10_000},new int[]{20,10_001,30,20,10_000},
            new int[]{20,30,0,20,10_000},new int[]{20,30,-1,20,10_000},new int[]{20,30,10_001,20,10_000},
            new int[]{20,30,30,0,10_000},new int[]{20,30,30,-1,10_000},new int[]{20,30,30,10_001,10_000},
            new int[]{20,30,30,20,0},new int[]{20,30,30,20,-1},new int[]{20,30,30,20,100_001});
    }

    private static PublicCustomerAuthenticationRateLimitPort.Key key(UUID site,
            PublicCustomerAuthenticationRateLimitPort.Operation operation) {
        return new PublicCustomerAuthenticationRateLimitPort.Key(site,operation);
    }
}
