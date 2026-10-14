package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeProviderStatePersistenceAdapter.Scenario;
import dev.genericpayments.platform.shared.adapter.out.observability.MicrometerStage2TelemetryAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PaymentConfirmationCoordinatorIntegrationTest extends ProviderTestFixture {
    @Test void unconfiguredMetricSiteFailsBeforeAttemptStart() {
        var p = save.save(payment());
        var coordinator = new PaymentConfirmationCoordinator(attemptPhase, new PaymentProviderRegistry(Map.of(account.provider(), fake)),
            new MicrometerStage2TelemetryAdapter(new SimpleMeterRegistry(), Set.of()), () -> 0);
        assertThatThrownBy(() -> coordinator.confirm(confirm(p))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payment_attempts")).isZero(); assertThat(count("payment_events")).isZero(); assertThat(count("fake_provider_operations")).isZero();
    }
    @Test void concurrentSameCommandResumesOneDurableAttemptAndOneProviderOperation() throws Exception {
        var p=save.save(payment());var command=confirm(p);var c=coordinator(new SimpleMeterRegistry(),false);
        var barrier=new java.util.concurrent.CyclicBarrier(4);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<java.util.concurrent.Future<Payment>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(() -> {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return c.confirm(command);}));
            for(var future:futures)assertThat(future.get(15,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.PROCESSING);
        }
        assertThat(count("payment_attempts")).isEqualTo(1);assertThat(count("fake_provider_operations")).isEqualTo(1);
        assertThat(count("payment_events")).isEqualTo(2);
    }
    @Test void lockedLeaseBoundaryRequiresTransactionAndRemainsSiteScoped() {
        var p=save.save(payment());
        assertThatThrownBy(() -> load.findForAttemptStart(site,p.id())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var command=new ConfirmPaymentCommand(new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),p.id(),account,new PaymentAttemptId(UUID.randomUUID()),dev.genericpayments.platform.sites.domain.PaymentMethod.CARD,"test-site","r","i");
        assertThatThrownBy(() -> coordinator(new SimpleMeterRegistry(),false).confirm(command)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payment_attempts")).isZero();assertThat(count("payment_events")).isZero();assertThat(count("fake_provider_objects")).isZero();
    }
    @Test void attemptAndJournalRollbackTogetherBeforeCallingProvider() {
        var p=save.save(payment());var command=confirm(p);var meters=new SimpleMeterRegistry();
        rejectJournal(PaymentEventType.ATTEMPT_CREATED);
        assertThatThrownBy(() -> coordinator(meters,false).confirm(command)).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_attempts")).isZero();assertThat(count("payment_events")).isZero();
        assertThat(count("fake_provider_objects")).isZero();assertThat(meters.getMeters()).isEmpty();
        assertThat(load.findById(site,p.id()).orElseThrow()).isEqualTo(p);
    }
    @Test void acknowledgementProjectionAttemptAndJournalRollbackThenResumeSameOperation() {
        var p=save.save(payment());var command=confirm(p);var meters=new SimpleMeterRegistry();var c=coordinator(meters,false);
        rejectJournal(PaymentEventType.PAYMENT_PROCESSING);
        assertThatThrownBy(() -> c.confirm(command)).isInstanceOf(RuntimeException.class);
        assertThat(load.findById(site,p.id()).orElseThrow()).isEqualTo(p);
        assertThat(attempts.findById(site,command.attemptId()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.CREATED);
        assertThat(count("payment_events")).isEqualTo(1);assertThat(count("fake_provider_operations")).isEqualTo(1);
        assertThat(meters.find("payment.transition").counter()).isNull();
        assertThat(meters.find("payment.provider.call").timer()).isNull();
        removeJournalFailure();assertThat(c.confirm(command).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(count("payment_attempts")).isEqualTo(1);assertThat(count("fake_provider_operations")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(2);
    }
    @ParameterizedTest @EnumSource(Scenario.class)
    void confirmationMapsEveryOutcomeWithoutSynchronousSuccess(Scenario scenario) {
        var p=save.save(payment().requiresPaymentMethod(INITIAL_TIME)); state.configure(request(p),scenario);
        var meters=new SimpleMeterRegistry();var c=coordinator(meters,false);var command=confirm(p);
        var result=c.confirm(command);
        assertThat(meters.get("payment.provider.call").timer().getId().getTag("result")).isEqualTo(scenario.name().toLowerCase(java.util.Locale.ROOT));
        assertThat(meters.get("payment.provider.call").timer().getId().getTag("operation")).isEqualTo("confirm");
        assertThat(result.status()).isNotEqualTo(PaymentStatus.SUCCEEDED);
        var attempt=attempts.findById(site,command.attemptId()).orElseThrow().value();
        assertThat(attempt.status()).isEqualTo(switch(scenario){case ACCEPTED -> PaymentAttemptStatus.REQUIRES_PAYMENT_METHOD;case PROCESSING -> PaymentAttemptStatus.PROCESSING;case REQUIRES_ACTION -> PaymentAttemptStatus.REQUIRES_ACTION;case RECOVERABLE_DECLINE,TERMINAL_DECLINE,TEMPORARILY_UNAVAILABLE -> PaymentAttemptStatus.FAILED;case UNKNOWN -> PaymentAttemptStatus.UNKNOWN;});
        assertThat(attempt.sequence()).isEqualTo(1);
        assertThat(events.findByPayment(site,p.id()).getFirst().type()).isEqualTo(PaymentEventType.ATTEMPT_CREATED);
        c.confirm(command);assertThat(count("payment_attempts")).isEqualTo(1);
        if(scenario==Scenario.UNKNOWN) {
            assertThatThrownBy(() -> c.confirm(confirm(p))).isInstanceOf(RuntimeException.class);
            assertThat(count("payment_attempts")).isEqualTo(1);
        }
        if(scenario==Scenario.RECOVERABLE_DECLINE) {
            c.confirm(confirm(p));assertThat(attempts.findByPayment(site,p.id())).extracting(a -> a.value().sequence()).containsExactly(1,2);
        }
    }
    @Test void leaseBlocksBothNewAndResumedOperation() {
        var p=save.save(payment());var command=confirm(p);attemptPhase.startAttempt(command);
        load.tryAcquireReconciliationLease(site,p.id(),"worker",INITIAL_TIME,INITIAL_TIME.plusSeconds(30));
        var c=coordinator(new SimpleMeterRegistry(),false);
        assertThatThrownBy(() -> c.confirm(command)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> c.confirm(confirm(p))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_provider_operations")).isZero();
    }
    @Test void thrownConfirmationRecordsUnknownAndBlocksNextAttempt() {
        var p=save.save(payment());var meters=new SimpleMeterRegistry();var c=coordinator(meters,true);var command=confirm(p);
        c.confirm(command);
        assertThat(attempts.findById(site,command.attemptId()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThatThrownBy(() -> c.confirm(confirm(p))).isInstanceOf(RuntimeException.class);
        assertThat(meters.get("payment.provider.unknown").counter().count()).isEqualTo(1);
    }
    PaymentConfirmationCoordinator coordinator(SimpleMeterRegistry meters,boolean failure) {
        var probe=new DelegatingProvider(fake) {
            @Override public ProviderResult confirmPayment(ProviderConfirmationRequest r) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(attempts.findById(site,r.attempt().id())).isPresent();
                if(failure) throw new IllegalStateException("unsafe provider text");
                return super.confirmPayment(r);
            }
        };
        return new PaymentConfirmationCoordinator(attemptPhase,new PaymentProviderRegistry(Map.of(account.provider(),probe)),new MicrometerStage2TelemetryAdapter(meters,Set.of("test-site")),() -> 0L);
    }
}
