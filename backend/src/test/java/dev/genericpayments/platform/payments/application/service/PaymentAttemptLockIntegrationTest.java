package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentAttemptLockIntegrationTest extends ProviderTestFixture {
    @Autowired PlatformTransactionManager transactions;

    @Test void attemptLockIsMandatorySiteScopedAndActuallyContendsInPostgres() throws Exception {
        var p=save.save(payment());var command=confirm(p);var started=attemptPhase.startAttempt(command);
        assertThatThrownBy(() -> attempts.findByIdForUpdate(site,command.attemptId())).isInstanceOf(IllegalTransactionStateException.class);
        var tx=new TransactionTemplate(transactions);
        Boolean absent=tx.execute(status -> attempts.findByIdForUpdate(new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),command.attemptId()).isEmpty());
        assertThat(absent).isTrue();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var holder=pool.submit(() -> tx.executeWithoutResult(status -> {
                assertThat(attempts.findByIdForUpdate(site,command.attemptId())).contains(started.attempt());
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Lock holder timed out"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            Throwable failure=catchThrowable(() -> tx.executeWithoutResult(status -> {
                jdbc.execute("set local lock_timeout='100ms'");
                attempts.findByIdForUpdate(site,command.attemptId());
            }));
            assertThat(failure).isNotNull();
            while(failure.getCause()!=null)failure=failure.getCause();
            assertThat(failure).isInstanceOf(SQLException.class);
            assertThat(((SQLException)failure).getSQLState()).isEqualTo("55P03");
            var acknowledgement=pool.submit(() -> attemptPhase.recordProviderAcknowledgementOrUnknown(command,
                new dev.genericpayments.platform.payments.application.model.ProviderResult.Accepted("fake_pi_"+p.id().value(),true)));
            // Observe PostgreSQL's actual lock waiter, not a sleep or an assumed scheduling delay.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
            while(System.nanoTime()<deadline&&!waiting) {
                waiting=jdbc.queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query like 'select a.id from payment_attempts%')",Boolean.class);
            }
            assertThat(waiting).isTrue();assertThat(acknowledgement.isDone()).isFalse();
            assertThat(attempts.findById(site,command.attemptId())).contains(started.attempt());
            assertThat(count("payment_events")).isEqualTo(1);
            release.countDown();holder.get(10,TimeUnit.SECONDS);
            assertThat(acknowledgement.get(10,TimeUnit.SECONDS).payment().status()).isEqualTo(dev.genericpayments.platform.payments.domain.PaymentStatus.PROCESSING);
        } finally { release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        assertThat(attempts.findById(site,command.attemptId()).orElseThrow().value().status()).isEqualTo(dev.genericpayments.platform.payments.domain.PaymentAttemptStatus.PROCESSING);
        assertThat(count("payment_events")).isEqualTo(2);assertThat(count("fake_provider_operations")).isZero();
    }
}
