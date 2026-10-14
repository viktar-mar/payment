package dev.genericpayments.platform.webhooks.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import dev.genericpayments.platform.shared.adapter.out.observability.PendingOutboxGaugeRefreshAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real independent transactions occupy every spare connection, not mocked pool calls. */
abstract class PendingOutboxPoolPressureTestSupport extends CheckoutTestFixture {
    @Autowired MeterRegistry registry;
    @Autowired OutboxWorker worker;
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired PaymentOutboxPort snapshots;
    @Autowired AppendPaymentEventPort append;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.zaxxer.hikari.HikariDataSource pool;
    @Autowired dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort telemetry;
    abstract int poolSize();

    @Test void burstSignalsAreCoalescedAndOwnedShutdownInterruptsPoolAcquisition() throws Exception {
        worker.runOnce();
        var acquired=new CountDownLatch(poolSize());var release=new CountDownLatch(1);
        var holders=Executors.newFixedThreadPool(poolSize());var futures=new ArrayList<Future<?>>();
        var existing=Set.copyOf(Thread.getAllStackTraces().keySet());
        var owned=new PendingOutboxGaugeRefreshAdapter(jdbc,telemetry,Set.of("test-site"),transactions);
        try {
            for(int i=0;i<poolSize();i++)futures.add(holders.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForObject("select 1",Integer.class);acquired.countDown();
                try {if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("holder release timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            })));
            assertThat(acquired.await(10,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<1000;i++)owned.requestRefresh();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(ownedThreads(existing)).hasSize(1));
            await().atMost(Duration.ofSeconds(1)).untilAsserted(()->
                assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).as("owned worker really waits on the exhausted pool").isEqualTo(1));
            var threads=ownedThreads(existing);
            owned.close();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(threads).allMatch(thread->!thread.isAlive()));
            assertThatCode(owned::requestRefresh).doesNotThrowAnyException();
            assertThat(ownedThreads(existing)).isEmpty();
        } finally {
            owned.close();release.countDown();
            try {for(var future:futures)future.get(10,TimeUnit.SECONDS);}
            finally {holders.shutdownNow();assertThat(holders.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
        }
        assertThat(count("outbox_events")).isZero();assertThat(count("outbox_deliveries")).isZero();
    }

    private static List<Thread> ownedThreads(Set<Thread> existing) {
        return Thread.getAllStackTraces().keySet().stream().filter(thread->!existing.contains(thread)&&
            thread.getName().equals("payment-outbox-gauge-refresh")&&thread.isAlive()).toList();
    }

    @Test void committedSnapshotReturnsWithoutWaitingForAnotherConnectionInASaturatedPool() throws Exception {
        assertThat(transactions).isInstanceOf(JpaTransactionManager.class);
        worker.runOnce();
        var fact=fact();
        int holders=poolSize()-1;
        var acquired=new CountDownLatch(holders);
        var release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(Math.max(1,holders));
        var futures=new ArrayList<Future<?>>();
        Throwable failure;
        long elapsed;
        try {
            for(int i=0;i<holders;i++)futures.add(executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForObject("select 1",Integer.class);
                acquired.countDown();
                try {if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("holder release timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            })));
            assertThat(acquired.await(10,TimeUnit.SECONDS)).as("all spare connections occupied").isTrue();
            long started=System.nanoTime();
            failure=catchThrowable(()->snapshotFuture(fact));
            elapsed=System.nanoTime()-started;
        } finally {
            release.countDown();
            try {for(var future:futures)future.get(10,TimeUnit.SECONDS);}
            finally {executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
        }
        assertThat(count("outbox_events")).as("business snapshot committed despite telemetry pressure").isEqualTo(1);
        assertThat(failure).as("already committed business call must return successfully").isNull();
        assertThat(Duration.ofNanos(elapsed)).as("caller must not wait for the 2 second pool acquisition timeout").isLessThan(Duration.ofSeconds(1));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertPending(1));
        assertNoDelivery(fact);
        jdbc.update("update outbox_events set status='DEAD' where id=?",fact.event().id());
        worker.runOnce();assertPending(0);assertNoDelivery(fact);
    }

    @Test void refreshQueryFailureCannotReportAnAlreadyCommittedSnapshotAsFailed() {
        worker.runOnce();var fact=fact();
        var failures=new AtomicInteger();
        var logger=(Logger)LoggerFactory.getLogger(PendingOutboxGaugeRefreshAdapter.class);
        var evidence=new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if(event.getFormattedMessage().equals("Pending outbox telemetry refresh failed: BadSqlGrammarException"))failures.incrementAndGet();
            }
        };
        evidence.start();logger.addAppender(evidence);
        Throwable failure;
        try {
            failure=catchThrowable(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                snapshots.snapshot(fact.payment(),fact.event(),INITIAL_TIME);
                jdbc.update("update outbox_events set next_attempt_at=? where id=?",Timestamp.from(INITIAL_TIME.plusSeconds(43200)),fact.event().id());
                // Commit a real schema fault in this exclusively owned test database.
                jdbc.execute("alter table outbox_retry_requests rename to fix2_unavailable_retry_requests");
            }));
            assertThat(count("outbox_events")).isEqualTo(1);
            assertThat(failure).as("telemetry SQL failure must stay off the business caller").isNull();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(failures.get()).isEqualTo(1));
            assertNoDelivery(fact);
        } finally {
            logger.detachAppender(evidence);evidence.stop();
            jdbc.execute("alter table if exists fix2_unavailable_retry_requests rename to outbox_retry_requests");
        }
        // The normal independent idle refresh recovers after telemetry becomes available again.
        worker.runOnce();assertPending(1);assertNoDelivery(fact);
        jdbc.update("update outbox_events set status='DEAD' where id=?",fact.event().id());
        worker.runOnce();assertPending(0);
    }

    private void snapshotFuture(Fact fact) {
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            snapshots.snapshot(fact.payment(),fact.event(),INITIAL_TIME);
            jdbc.update("update outbox_events set next_attempt_at=? where id=?",Timestamp.from(INITIAL_TIME.plusSeconds(43200)),fact.event().id());
        });
    }
    private void assertNoDelivery(Fact fact) {
        assertThat(jdbc.queryForObject("select attempt_count from outbox_events where id=?",Integer.class,fact.event().id())).isZero();
        assertThat(count("outbox_deliveries")).isZero();
    }
    private void assertPending(double expected) {
        var gauge=registry.find("payment.outbox.pending").tags("site","test-site","provider","fake").gauge();
        assertThat(gauge).isNotNull();assertThat(gauge.value()).isEqualTo(expected);
    }
    private Fact fact() {
        callbacks.configure(site.value(),"https://example.com/callback",true);
        var payment=save.save(payment().succeeded(INITIAL_TIME));
        var event=new TransactionTemplate(transactions).execute(tx->append.append(payment.id(),sequence->
            new PaymentEvent(UUID.randomUUID(),payment.id(),sequence,site,account.id(),PaymentEventType.PAYMENT_SUCCEEDED,
                PaymentEventSource.PROVIDER_EVENT,payment.money(),null,null,INITIAL_TIME,INITIAL_TIME,"pool-pressure-test",null,"a".repeat(64),Map.of())));
        return new Fact(payment,event);
    }
    private record Fact(Payment payment,PaymentEvent event) {}
}
