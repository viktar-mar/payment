package dev.genericpayments.platform.webhooks.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Missing idle refresh or pre-commit publication must fail these durable-state checks. */
@TestPropertySource(properties="payment.callbacks.delay-ms=60000")
@DirtiesContext(classMode=DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PendingOutboxGaugeIntegrationTest extends CheckoutTestFixture {
    @Autowired MeterRegistry registry;
    @Autowired OutboxWorker worker;
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired PaymentOutboxPort snapshots;
    @Autowired AppendPaymentEventPort append;
    @Autowired PlatformTransactionManager transactions;

    @Test void freshRegistryExposesFutureBacklogOnIdlePassAndZeroAfterItClears() {
        assertThat(registry.find("payment.outbox.delivered").counter()).isNull();
        var fact=fact();
        new TransactionTemplate(transactions).executeWithoutResult(tx->snapshots.snapshot(fact.payment(),fact.event(),INITIAL_TIME));
        jdbc.update("update outbox_events set next_attempt_at=? where id=?",Timestamp.from(INITIAL_TIME.plusSeconds(43200)),fact.event().id());
        worker.runOnce();
        assertThat(jdbc.queryForObject("select attempt_count from outbox_events where id=?",Integer.class,fact.event().id())).isZero();
        assertThat(count("outbox_deliveries")).isZero();
        assertPending(1);
        jdbc.update("update outbox_events set status='DEAD' where id=?",fact.event().id());
        worker.runOnce();
        assertPending(0);
    }

    @Test void newSnapshotPublishesOnlyCommittedWorkWithoutWaitingForAClaim() {
        worker.runOnce();assertPending(0);
        var first=fact();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            snapshots.snapshot(first.payment(),first.event(),INITIAL_TIME);
            assertPending(0);
        });
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertPending(1));
        var rolledBack=fact();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            snapshots.snapshot(rolledBack.payment(),rolledBack.event(),INITIAL_TIME);
            assertPending(1);tx.setRollbackOnly();
        });
        assertPending(1);assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select attempt_count from outbox_events",Integer.class)).isZero();
    }

    private void assertPending(double expected) {
        var gauge=registry.find("payment.outbox.pending").tags("site","test-site","provider","fake").gauge();
        assertThat(gauge).as("current durable pending backlog gauge").isNotNull();
        assertThat(gauge.value()).isEqualTo(expected);
    }
    private Fact fact() {
        callbacks.configure(site.value(),"https://example.com/callback",true);
        var payment=save.save(payment().succeeded(INITIAL_TIME));
        var event=new TransactionTemplate(transactions).execute(tx->append.append(payment.id(),sequence->
            new PaymentEvent(UUID.randomUUID(),payment.id(),sequence,site,account.id(),PaymentEventType.PAYMENT_SUCCEEDED,
                PaymentEventSource.PROVIDER_EVENT,payment.money(),null,null,INITIAL_TIME,INITIAL_TIME,"gauge-test",null,"a".repeat(64),Map.of())));
        return new Fact(payment,event);
    }
    private record Fact(Payment payment,PaymentEvent event) {}
}
