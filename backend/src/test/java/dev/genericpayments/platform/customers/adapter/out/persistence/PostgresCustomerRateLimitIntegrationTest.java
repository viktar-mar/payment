package dev.genericpayments.platform.customers.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresCustomerRateLimitIntegrationTest extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DistributedCustomerRateLimitPort limits;
    SiteId site;

    @BeforeEach void prepare() {
        clearDatabase(); site=createSite("rate-site","rate-account");
    }

    @Test void sourceLimitIsAtomicUnderConcurrencyAndRollsBackOtherDimensionsOnRejectedAcquisitions() throws Exception {
        int calls=20; AtomicInteger admitted=new AtomicInteger(),rejected=new AtomicInteger();
        ExecutorService executor=Executors.newFixedThreadPool(calls); CountDownLatch ready=new CountDownLatch(calls),start=new CountDownLatch(1);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(int index=0;index<calls;index++) {
                int value=index;
                futures.add(executor.submit(() -> {
                    ready.countDown(); start.await();
                    try { limits.acquire(site,digest("site"),digest("email"+value),digest("same-source"),INITIAL_TIME); admitted.incrementAndGet(); }
                    catch(PaymentApplicationException expected) { assertThat(expected.code()).isEqualTo("OTP_RATE_LIMITED");rejected.incrementAndGet(); }
                    return null;
                }));
            }
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(Future<?> future:futures)future.get(15,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}

        assertThat(admitted).hasValue(10); assertThat(rejected).hasValue(10);
        assertThat(jdbc.queryForObject("select request_count from customer_rate_limit_buckets where bucket_type='SOURCE_IP'",Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select request_count from customer_rate_limit_buckets where bucket_type='SITE'",Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from customer_rate_limit_buckets where bucket_type='EMAIL'",Integer.class)).isEqualTo(10);
    }

    @Test void siteLimitIsIndependentlyAtomicUnderConcurrency() throws Exception {
        var outcome=concurrentAcquire(70,index -> new Attempt(digest("site"),digest("email-"+index),digest("source-"+index)));
        assertThat(outcome.admitted()).isEqualTo(60);
        assertThat(outcome.rejected()).isEqualTo(10);
        assertThat(jdbc.queryForObject("select request_count from customer_rate_limit_buckets where bucket_type='SITE'",Integer.class)).isEqualTo(60);
    }

    @Test void normalizedEmailLimitIsIndependentlyAtomicUnderConcurrency() throws Exception {
        var outcome=concurrentAcquire(12,index -> new Attempt(digest("site"),digest("same-normalized-email"),digest("source-"+index)));
        assertThat(outcome.admitted()).isEqualTo(5);
        assertThat(outcome.rejected()).isEqualTo(7);
        assertThat(jdbc.queryForObject("select request_count from customer_rate_limit_buckets where bucket_type='EMAIL'",Integer.class)).isEqualTo(5);
    }

    @Test void equalDigestsAreIsolatedBySiteAndCleanupIsBounded() {
        SiteId other=createSite("rate-site-two","rate-account-two");
        for(int index=0;index<10;index++) {
            limits.acquire(site,digest("site-a"),digest("email-a"+index),digest("shared-source"),INITIAL_TIME);
            limits.acquire(other,digest("site-b"),digest("email-b"+index),digest("shared-source"),INITIAL_TIME);
        }
        assertThat(jdbc.queryForObject("select count(*) from customer_rate_limit_buckets where bucket_type='SOURCE_IP'",Integer.class)).isEqualTo(2);
        assertThat(limits.cleanupExpired(INITIAL_TIME.plus(Duration.ofMinutes(11)),2)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from customer_rate_limit_buckets",Integer.class)).isEqualTo(22);
    }

    private SiteId createSite(String key,String accountKey) {
        SiteId id=new SiteId(UUID.randomUUID());UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,?, 'FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account,accountKey);
        jdbc.update("insert into sites values (?,?, 'Rate',?,'ACTIVE','AUTOMATIC',now(),now(),0)",id.value(),key,account);
        return id;
    }
    private Outcome concurrentAcquire(int calls,java.util.function.IntFunction<Attempt> attempts) throws Exception {
        AtomicInteger admitted=new AtomicInteger(),rejected=new AtomicInteger();
        ExecutorService executor=Executors.newFixedThreadPool(calls); CountDownLatch ready=new CountDownLatch(calls),start=new CountDownLatch(1);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(int index=0;index<calls;index++) {int value=index;futures.add(executor.submit(() -> {
                ready.countDown();start.await();Attempt attempt=attempts.apply(value);
                try {limits.acquire(site,attempt.site(),attempt.email(),attempt.source(),INITIAL_TIME);admitted.incrementAndGet();}
                catch(PaymentApplicationException expected){assertThat(expected.code()).isEqualTo("OTP_RATE_LIMITED");rejected.incrementAndGet();}
                return null;
            }));}
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(Future<?> future:futures)future.get(20,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}
        return new Outcome(admitted.get(),rejected.get());
    }
    private record Attempt(KeyedDigestPort.DigestValue site,KeyedDigestPort.DigestValue email,KeyedDigestPort.DigestValue source) {}
    private record Outcome(int admitted,int rejected) {}
    private static KeyedDigestPort.DigestValue digest(String value){return new KeyedDigestPort.DigestValue(value,"v1");}
}
