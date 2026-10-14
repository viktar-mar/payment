package dev.genericpayments.platform.shared.adapter.out.observability;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Real production serialization/executor; controlled transaction boundary, no JDBC/network timing. */
class PendingOutboxGaugeRefreshLifecycleTest {
    @Test void burstDuringReadRetainsFinalSignalAndSerializesSynchronousPublication() throws Exception {
        var transactions=new ControlledTransactions();transactions.release.countDown();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var pending=new AtomicLong(1);
        var jdbc=new JdbcTemplate(){
            @Override public <T> T queryForObject(String sql,Class<T> requiredType,Object... args) {
                long snapshot=pending.get();
                if(calls.incrementAndGet()==1){entered.countDown();awaitLatch(release);}
                return requiredType.cast(snapshot);
            }
        };
        var registry=new SimpleMeterRegistry();
        var adapter=new PendingOutboxGaugeRefreshAdapter(jdbc,new MicrometerStage2TelemetryAdapter(registry,Set.of("demo")),Set.of("demo"),transactions);
        var failure=new AtomicReference<Throwable>();
        var synchronous=new Thread(()->{try{adapter.refreshPending();}catch(Throwable thrown){failure.set(thrown);}},"synchronous-serialization-test");
        try {
            adapter.requestRefresh();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            pending.set(7);for(int i=0;i<1000;i++)adapter.requestRefresh();synchronous.start();
            await().atMost(Duration.ofSeconds(2)).until(()->synchronous.getState()==Thread.State.WAITING);
            assertThat(transactions.starts.get()).as("synchronous refresh cannot race an older publication").isEqualTo(1);
            release.countDown();synchronous.join(2000);assertThat(synchronous.isAlive()).isFalse();assertThat(failure.get()).isNull();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->{
                assertThat(transactions.completed.get()).as("initial read, synchronous read and one coalesced final read").isEqualTo(3);
                assertThat(registry.get("payment.outbox.pending").tags("site","demo","provider","fake").gauge().value()).isEqualTo(7);
            });
            assertThat(transactions.maxActive.get()).isEqualTo(1);
        } finally {release.countDown();synchronous.join(2000);adapter.close();registry.close();}
    }

    @Test void queryFailureIsContainedAndALaterSignalRecoversWithoutBusyRetry() {
        var transactions=new ControlledTransactions();transactions.release.countDown();var calls=new AtomicInteger();
        var jdbc=new JdbcTemplate(){
            @Override public <T> T queryForObject(String sql,Class<T> requiredType,Object... args) {
                if(calls.incrementAndGet()==1)throw new org.springframework.dao.DataAccessResourceFailureException("controlled unavailable dependency");
                return requiredType.cast(Long.valueOf(4));
            }
        };
        var registry=new SimpleMeterRegistry();
        var adapter=new PendingOutboxGaugeRefreshAdapter(jdbc,new MicrometerStage2TelemetryAdapter(registry,Set.of("demo")),Set.of("demo"),transactions);
        try {
            adapter.requestRefresh();
            await().atMost(Duration.ofSeconds(2)).until(()->transactions.completed.get()==1 && !scheduled(adapter));
            assertThat(transactions.starts.get()).as("failure alone schedules no retry loop").isEqualTo(1);
            adapter.requestRefresh();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->{
                var gauge=registry.find("payment.outbox.pending").tags("site","demo","provider","fake").gauge();
                assertThat(gauge).isNotNull();assertThat(gauge.value()).isEqualTo(4);
            });
            assertThat(transactions.starts.get()).isEqualTo(2);
        } finally {adapter.close();registry.close();}
    }

    @Test void executorRejectionAndPostCloseRequestsAreHarmlessAndLeaveNoScheduledOwner() {
        var transactions=new ControlledTransactions();transactions.release.countDown();var registry=new SimpleMeterRegistry();
        var adapter=new PendingOutboxGaugeRefreshAdapter(new CountingJdbc(),new MicrometerStage2TelemetryAdapter(registry,Set.of("demo")),Set.of("demo"),transactions);
        try {
            // A real rejected submission, independent of the entry closed guard; no mock executor.
            executor(adapter).shutdownNow();
            assertThatCode(()->{for(int i=0;i<1000;i++)adapter.requestRefresh();}).doesNotThrowAnyException();
            assertThat(scheduled(adapter)).isFalse();assertThat(transactions.starts.get()).isZero();
            adapter.close();assertThatCode(adapter::requestRefresh).doesNotThrowAnyException();
            assertThat(executor(adapter).isTerminated()).isTrue();
        } finally {adapter.close();registry.close();}
    }

    @Test void interruptedSynchronousWaitRestoresInterruptAndDoesNotAcquireATransaction() throws Exception {
        var transactions=new ControlledTransactions();var registry=new SimpleMeterRegistry();
        var adapter=new PendingOutboxGaugeRefreshAdapter(new CountingJdbc(),new MicrometerStage2TelemetryAdapter(registry,Set.of("demo")),Set.of("demo"),transactions);
        var owner=new Thread(adapter::refreshPending,"synchronous-owner-test");var interrupted=new AtomicReference<Boolean>();
        var waiter=new Thread(()->{adapter.refreshPending();interrupted.set(Thread.currentThread().isInterrupted());},"synchronous-waiter-test");
        try {
            owner.start();assertThat(transactions.entered.await(2,TimeUnit.SECONDS)).isTrue();waiter.start();
            await().atMost(Duration.ofSeconds(2)).until(()->waiter.getState()==Thread.State.WAITING);
            waiter.interrupt();waiter.join(2000);
            assertThat(waiter.isAlive()).isFalse();assertThat(interrupted.get()).isTrue();assertThat(transactions.starts.get()).isEqualTo(1);
        } finally {transactions.release.countDown();owner.join(2000);waiter.interrupt();waiter.join(2000);adapter.close();registry.close();}
    }

    @Test void closeAfterOwnershipAcquisitionStillPreventsTransactionStart() throws Exception {
        var transactions=new ControlledTransactions();transactions.release.countDown();
        var registry=new SimpleMeterRegistry();var jdbc=new CountingJdbc();
        var adapter=new PendingOutboxGaugeRefreshAdapter(jdbc,new MicrometerStage2TelemetryAdapter(registry,Set.of("demo")),Set.of("demo"),transactions);
        // Test-only scheduler barrier: pause immediately AFTER real lock acquisition, before the lifecycle guard.
        var ownership=new AcquiredOwnershipBarrier();
        var field=PendingOutboxGaugeRefreshAdapter.class.getDeclaredField("refreshOwnership");
        field.setAccessible(true);field.set(adapter,ownership);
        var closer=new Thread(adapter::close,"adapter-close-test");
        try {
            adapter.requestRefresh();assertThat(ownership.acquired.await(2,TimeUnit.SECONDS)).isTrue();
            closer.start();
            await().atMost(Duration.ofSeconds(2)).until(()->executor(adapter).isShutdown());
            ownership.release.countDown();closer.join(2000);
            assertThat(closer.isAlive()).as("close finishes after acquired owner observes closure").isFalse();
            assertThat(transactions.starts.get()).as("post-acquisition closed guard prevents any transaction").isZero();
            assertThat(jdbc.asyncQueries.get()).isZero();
        } finally {ownership.release.countDown();closer.join(2000);adapter.close();registry.close();}
    }

    @Test void synchronousRefreshAndRequestsAfterCloseDoNotStartTransactions() {
        var transactions=new ControlledTransactions();transactions.release.countDown();
        var registry=new SimpleMeterRegistry();
        var adapter=new PendingOutboxGaugeRefreshAdapter(new CountingJdbc(),new MicrometerStage2TelemetryAdapter(registry,Set.of("demo")),Set.of("demo"),transactions);
        try {adapter.close();for(int i=0;i<1000;i++)adapter.requestRefresh();adapter.refreshPending();
            assertThat(transactions.starts.get()).isZero();
        } finally {adapter.close();registry.close();}
    }

    @Test void closeAbandonsWorkerWaitingBehindSynchronousRefreshWithoutStartingAnotherTransaction() throws Exception {
        var transactions=new ControlledTransactions();
        var registry=new SimpleMeterRegistry();
        var jdbc=new CountingJdbc();
        var adapter=new PendingOutboxGaugeRefreshAdapter(jdbc,new MicrometerStage2TelemetryAdapter(registry,Set.of("demo")),Set.of("demo"),transactions);
        var failure=new AtomicReference<Throwable>();
        var synchronous=new Thread(()->{
            try {adapter.refreshPending();}catch(Throwable thrown){failure.set(thrown);}
        },"synchronous-idle-refresh-test");
        var ownedWorker=recordOwnedWorker(adapter);
        Thread owned=null;boolean stoppedBeforeOwnerReleased=false;long elapsed=0;
        try {
            synchronous.start();
            assertThat(transactions.entered.await(2,TimeUnit.SECONDS)).as("synchronous refresh owns serialization inside transaction acquisition").isTrue();
            adapter.requestRefresh();
            await().atMost(Duration.ofSeconds(2)).until(()->waitingWorker(ownedWorker.get()));
            owned=ownedWorker.get();
            long start=System.nanoTime();adapter.close();
            // Executor TERMINATED can precede the final Thread.run return: await this exact owned thread.
            owned.join(500);elapsed=System.nanoTime()-start;
            stoppedBeforeOwnerReleased=!owned.isAlive();
        } finally {
            transactions.release.countDown();synchronous.join(2000);
            if(owned!=null)owned.join(2000);
            adapter.close();registry.close();
        }
        assertThat(failure.get()).isNull();assertThat(synchronous.isAlive()).isFalse();
        boolean stopped=stoppedBeforeOwnerReleased;long closeNanos=elapsed;
        org.assertj.core.api.SoftAssertions.assertSoftly(soft->{
            soft.assertThat(stopped).as("owned waiting worker terminates before synchronous owner releases serialization").isTrue();
            soft.assertThat(Duration.ofNanos(closeNanos)).as("lock contention must not consume the five-second shutdown timeout").isLessThan(Duration.ofSeconds(1));
            soft.assertThat(transactions.starts.get()).as("no new transaction starts after close").isEqualTo(1);
            soft.assertThat(jdbc.asyncQueries.get()).as("no waiting-worker query begins after close").isZero();
        });
    }

    private static boolean waitingWorker(Thread thread) {
        return thread!=null && (thread.getState()==Thread.State.BLOCKED || thread.getState()==Thread.State.WAITING) &&
            java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame->frame.getClassName().equals(PendingOutboxGaugeRefreshAdapter.class.getName()) && frame.getMethodName().equals("refreshPending"));
    }

    private static AtomicReference<Thread> recordOwnedWorker(PendingOutboxGaugeRefreshAdapter adapter) {
        var pool=(ThreadPoolExecutor)executor(adapter);var original=pool.getThreadFactory();
        var owned=new AtomicReference<Thread>();
        pool.setThreadFactory(task->{var thread=original.newThread(task);owned.set(thread);return thread;});
        return owned;
    }

    private static ExecutorService executor(PendingOutboxGaugeRefreshAdapter adapter) {
        try {var field=PendingOutboxGaugeRefreshAdapter.class.getDeclaredField("executor");field.setAccessible(true);return (ExecutorService)field.get(adapter);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static boolean scheduled(PendingOutboxGaugeRefreshAdapter adapter) {
        try {var field=PendingOutboxGaugeRefreshAdapter.class.getDeclaredField("scheduled");field.setAccessible(true);return ((java.util.concurrent.atomic.AtomicBoolean)field.get(adapter)).get();}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static final class AcquiredOwnershipBarrier extends ReentrantLock {
        final CountDownLatch acquired=new CountDownLatch(1),release=new CountDownLatch(1);
        @Override public void lockInterruptibly() throws InterruptedException {
            super.lockInterruptibly();acquired.countDown();
            boolean interrupted=false;
            // Model scheduler preemption, not an interruptible dependency: the guard itself must stop work.
            for(;;)try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();break;}
            catch(InterruptedException ignored){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();
        }
    }

    private static class ControlledTransactions implements PlatformTransactionManager {
        final AtomicInteger starts=new AtomicInteger();
        final AtomicInteger active=new AtomicInteger(),maxActive=new AtomicInteger(),completed=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            maxActive.accumulateAndGet(active.incrementAndGet(),Math::max);
            if(starts.incrementAndGet()==1){entered.countDown();awaitLatch(release);}
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) {active.decrementAndGet();completed.incrementAndGet();}
        @Override public void rollback(TransactionStatus status) {active.decrementAndGet();completed.incrementAndGet();}
    }
    private static class CountingJdbc extends JdbcTemplate {
        final AtomicInteger asyncQueries=new AtomicInteger();
        @Override public <T> T queryForObject(String sql,Class<T> requiredType,Object... args) {
            if(Thread.currentThread().getName().equals("payment-outbox-gauge-refresh"))asyncQueries.incrementAndGet();
            return requiredType.cast(Long.valueOf(1));
        }
    }
    private static void awaitLatch(CountDownLatch latch) {
        try {assertThat(latch.await(15,TimeUnit.SECONDS)).as("controlled boundary released").isTrue();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
}
