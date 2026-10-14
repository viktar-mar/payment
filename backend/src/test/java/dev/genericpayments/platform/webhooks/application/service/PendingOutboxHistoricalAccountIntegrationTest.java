package dev.genericpayments.platform.webhooks.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.shared.adapter.out.observability.PendingOutboxGaugeRefreshAdapter;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.*;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Replacing historical account attribution with Site routing or all-provider totals must fail. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PendingOutboxHistoricalAccountIntegrationTest extends CheckoutTestFixture {
    @Autowired MeterRegistry registry;
    @Autowired Stage2TelemetryPort telemetry;
    @Autowired PendingOutboxTelemetryPort background;
    @Autowired OutboxPort outbox;
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired PaymentOutboxPort snapshots;
    @Autowired AppendPaymentEventPort append;
    @Autowired PlatformTransactionManager transactions;
    private UUID automaticFake;

    @Test void independentRefreshCountsAutomaticAndManualRowsByImmutableAccountAfterSiteSwitch() {
        mixedBacklog();
        try(var refresh=new PendingOutboxGaugeRefreshAdapter(jdbc,telemetry,Set.of("test-site"),transactions)) {
            refresh.refreshPending();assertGauges(2,2);
            jdbc.update("update sites set payment_account_id=? where id=?",account.id().value(),site.value());
            refresh.refreshPending();assertGauges(2,2);
            jdbc.update("update outbox_events set status='DEAD' where id=?",automaticFake);
            refresh.refreshPending();assertGauges(1,2);
            var manual=outbox.claimManual("refresh",10,INITIAL_TIME,Duration.ofSeconds(30)).stream().filter(c->c.context().provider()==Provider.FAKE).findFirst().orElseThrow();
            assertThat(outbox.complete(manual,CallbackHttpPort.Result.success(204),INITIAL_TIME)).isTrue();
            refresh.refreshPending();assertGauges(0,2);
        }
    }

    @ParameterizedTest @ValueSource(strings={"automatic-claim","automatic-complete","manual-enqueue","manual-complete","automatic-exhausted"})
    void immediateUpdatesNeverPublishAnotherProvidersBacklog(String action) {
        mixedBacklog();
        // A known correct prior observation isolates immediate updates from the independent refresh.
        telemetry.outboxPending(new Context("test-site",Provider.FAKE),2);
        telemetry.outboxPending(new Context("test-site",Provider.STRIPE),2);
        switch(action) {
            case "automatic-claim" -> {assertThat(outbox.claimAutomatic("test",10,INITIAL_TIME,Duration.ofSeconds(30))).hasSize(1);assertGauges(2,2);}
            case "automatic-complete" -> {
                var claim=outbox.claimAutomatic("test",10,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
                assertThat(outbox.complete(claim,CallbackHttpPort.Result.success(204),INITIAL_TIME)).isTrue();assertGauges(1,2);
            }
            case "manual-enqueue" -> {outbox.enqueue(snapshot(account.id(),true),OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME);assertGauges(3,2);}
            case "manual-complete" -> {
                var claims=outbox.claimManual("manual",10,INITIAL_TIME,Duration.ofSeconds(30));assertThat(claims).hasSize(2);assertGauges(2,2);
                var claim=claims.stream().filter(c->c.context().provider()==Provider.FAKE).findFirst().orElseThrow();
                assertThat(outbox.complete(claim,CallbackHttpPort.Result.success(204),INITIAL_TIME)).isTrue();assertGauges(1,2);
            }
            case "automatic-exhausted" -> {
                jdbc.update("update outbox_events set attempt_count=6 where id=?",automaticFake);
                assertThat(outbox.claimAutomatic("test",10,INITIAL_TIME,Duration.ofSeconds(30))).isEmpty();assertGauges(1,2);
            }
            default -> throw new AssertionError(action);
        }
        assertThat(outbox.pending(site.value())).isEqualTo(action.equals("manual-enqueue")?5:action.equals("automatic-claim")?4:3);
    }

    private void mixedBacklog() {
        // Stop only this test context's owned background refresher, preventing repair races.
        ((PendingOutboxGaugeRefreshAdapter)background).close();
        callbacks.configure(site.value(),"https://example.com/callback",true);
        automaticFake=snapshot(account.id(),false);
        var manualFake=snapshot(account.id(),true);
        var stripe=new PaymentAccountId(UUID.randomUUID());
        jdbc.update("insert into payment_accounts values (?,'historical-stripe','STRIPE','SANDBOX','ACTIVE','unused','unused',?,0)",stripe.value(),Timestamp.from(INITIAL_TIME));
        jdbc.update("update sites set payment_account_id=? where id=?",stripe.value(),site.value());
        var automaticStripe=snapshot(stripe,false);
        jdbc.update("update outbox_events set next_attempt_at=? where id=?",Timestamp.from(INITIAL_TIME.plusSeconds(3600)),automaticStripe);
        var manualStripe=snapshot(stripe,true);
        outbox.enqueue(manualFake,OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME);
        outbox.enqueue(manualStripe,OutboxPort.Mode.CURRENT_CONFIG_RETRY,INITIAL_TIME);
    }
    private UUID snapshot(PaymentAccountId pinned,boolean dead) {
        var payment=save.save(Payment.dynamic(new PaymentId(UUID.randomUUID()),site,pinned,"order-"+UUID.randomUUID(),Money.of(2000,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME).succeeded(INITIAL_TIME));
        return new TransactionTemplate(transactions).execute(tx->{
            var event=append.append(payment.id(),sequence->new PaymentEvent(UUID.randomUUID(),payment.id(),sequence,site,pinned,PaymentEventType.PAYMENT_SUCCEEDED,PaymentEventSource.PROVIDER_EVENT,payment.money(),null,null,INITIAL_TIME,INITIAL_TIME,"historical-gauge",null,"a".repeat(64),Map.of()));
            snapshots.snapshot(payment,event,INITIAL_TIME);
            if(dead)jdbc.update("update outbox_events set status='DEAD' where id=?",event.id());
            return event.id();
        });
    }
    private void assertGauges(double fake,double stripe) {
        assertThat(registry.find("payment.outbox.pending").tags("site","test-site","provider","fake").gauge().value()).as("historical FAKE backlog").isEqualTo(fake);
        assertThat(registry.find("payment.outbox.pending").tags("site","test-site","provider","stripe").gauge().value()).as("STRIPE backlog").isEqualTo(stripe);
    }
}
