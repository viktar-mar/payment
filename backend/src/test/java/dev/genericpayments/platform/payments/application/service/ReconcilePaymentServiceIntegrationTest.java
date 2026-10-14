package dev.genericpayments.platform.payments.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties="payment.telemetry.site-keys[0]=test-site")
class ReconcilePaymentServiceIntegrationTest extends ProviderTestFixture {
    @Autowired PaymentTransitionService transitions;
    @Autowired Stage2TelemetryPort telemetry;
    @Autowired Clock clock;
    @Autowired SavePaymentAttemptPort attemptSave;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    ReconcilePaymentService service(boolean failure,boolean mismatch,ProviderState.Status status) {
        var provider=new DelegatingProvider(fake) {
            @Override public ProviderState retrievePayment(ProviderPaymentLookup lookup) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments where id=?",String.class,lookup.paymentId().value())).isNotBlank();
                if(failure)throw new IllegalStateException("unsafe payload");return new ProviderState(mismatch?"wrong-object":lookup.reference(),status);
            }
        };
        return new ReconcilePaymentService(transitions,new PaymentProviderRegistry(Map.of(account.provider(),provider)),telemetry,clock,Duration.ofSeconds(30));
    }
    Payment persisted(){return save.save(payment().withProviderPaymentReference("object-1",INITIAL_TIME));}
    @Test void ambientTransactionRejectsBeforeLeaseOrProviderLookup() {
        var p=persisted();var before=jdbc.queryForMap("select * from payments where id=?",p.id().value());
        var calls=new java.util.concurrent.atomic.AtomicInteger();var rejection=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        var provider=new DelegatingProvider(fake) {
            @Override public ProviderState retrievePayment(ProviderPaymentLookup lookup) {
                calls.incrementAndGet();return new ProviderState(lookup.reference(),ProviderState.Status.UNKNOWN);
            }
        };
        var orchestrator=new ReconcilePaymentService(transitions,new PaymentProviderRegistry(Map.of(account.provider(),provider)),telemetry,clock,Duration.ofSeconds(30));
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            try { orchestrator.reconcile(site,p.id(),"ambient"); }catch(RuntimeException failure){rejection.set(failure);}
        });
        assertThat(calls.get()).isZero();
        assertThat(jdbc.queryForMap("select * from payments where id=?",p.id().value())).isEqualTo(before);
        assertThat(rejection.get()).isInstanceOf(IllegalStateException.class).hasMessage("Provider coordinator requires no ambient transaction");
        assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();
    }
    @Test void lookupOutsideTransactionResolvesUnknownAndRepeatedTruthIsNoop() {
        var p=persisted();var a=attemptSave.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"attempt","CARD",INITIAL_TIME).unknown("UNKNOWN",INITIAL_TIME),INITIAL_TIME);
        var s=service(false,false,ProviderState.Status.SUCCEEDED);assertThat(s.reconcile(site,p.id(),"request-1").status()).isEqualTo(PaymentStatus.SUCCEEDED);
        s.reconcile(site,p.id(),"request-2");assertThat(count("payment_events")).isEqualTo(1);assertThat(count("outbox_events")).isZero();
        assertThat(events.findByPayment(site,p.id()).getFirst().source()).isEqualTo(PaymentEventSource.RECONCILIATION);
        assertThat(events.findByPayment(site,p.id()).getFirst().providerEventId()).isNull();
        assertThat(attempts.findById(site,a.value().id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments",String.class)).isNull();
    }
    @Test void liveLeaseConflictsAndExpiredLeaseRecovers() {
        var p=persisted();load.tryAcquireReconciliationLease(site,p.id(),"other",INITIAL_TIME,INITIAL_TIME.plusSeconds(10));
        var s=service(false,false,ProviderState.Status.PROCESSING);assertThatThrownBy(() -> s.reconcile(site,p.id(),"request")).isInstanceOf(RuntimeException.class);
        TestClock.set(INITIAL_TIME.plusSeconds(11));assertThat(s.reconcile(site,p.id(),"request").status()).isEqualTo(PaymentStatus.PROCESSING);
    }
    @Test void failedLookupRetainsFiniteLeaseAndCreatesNoFabricatedFact() {
        var p=persisted();assertThatThrownBy(() -> service(true,false,ProviderState.Status.SUCCEEDED).reconcile(site,p.id(),"request")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select reconciliation_lease_expires_at from payments",java.sql.Timestamp.class).toInstant()).isEqualTo(INITIAL_TIME.plusSeconds(30));assertThat(count("payment_events")).isZero();
    }
    @Test void mismatchedProviderReferenceRetainsLease() {
        var p=persisted();assertThatThrownBy(() -> service(false,true,ProviderState.Status.SUCCEEDED).reconcile(site,p.id(),"request")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments",String.class)).isNotNull();assertThat(count("payment_events")).isZero();
    }
    @Test void unknownLookupClearsLeaseWithoutChangingProjectionOrAttempt() {
        var p=persisted();service(false,false,ProviderState.Status.UNKNOWN).reconcile(site,p.id(),"request");assertThat(count("payment_events")).isZero();assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments",String.class)).isNull();assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.CREATED);
    }
    @Test void knownSameProjectionResolvesLatestUnknownAttemptWithoutInventingPaymentEvent() {
        var p=save.save(persisted().processing(INITIAL_TIME));var a=attemptSave.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"unknown-attempt","CARD",INITIAL_TIME).unknown("UNKNOWN",INITIAL_TIME),INITIAL_TIME);
        service(false,false,ProviderState.Status.PROCESSING).reconcile(site,p.id(),"request");
        assertThat(attempts.findById(site,a.value().id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.PROCESSING);assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();
    }
    @Test void completionFailureRetainsLeaseAndRollsBackProjection() {
        var p=persisted();rejectJournal(PaymentEventType.PAYMENT_SUCCEEDED);assertThatThrownBy(() -> service(false,false,ProviderState.Status.SUCCEEDED).reconcile(site,p.id(),"request")).isInstanceOf(RuntimeException.class);
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("payment_events")).isZero();assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments",String.class)).isNotNull();
    }
    @Test void wrongSiteAndMissingReferenceFailBeforeLease() {
        var p=persisted();assertThatThrownBy(() -> service(false,false,ProviderState.Status.SUCCEEDED).reconcile(new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),p.id(),"request")).isInstanceOf(RuntimeException.class);
        var noRef=save.save(payment());assertThatThrownBy(() -> service(false,false,ProviderState.Status.SUCCEEDED).reconcile(site,noRef.id(),"request")).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForList("select reconciliation_lease_owner from payments",String.class)).containsOnlyNulls();
    }
    @Test void reconciliationSnapshotsCallbackThroughSameKernel() {
        var p=persisted();var secret=UUID.randomUUID();var at=java.sql.Timestamp.from(INITIAL_TIME);
        jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at) values (?,?,1,?,?,'test-v1',?)",secret,site.value(),new byte[32],new byte[12],at);
        jdbc.update("insert into site_callback_configs(site_id,endpoint_url,active_secret_version_id,enabled,created_at,updated_at) values (?,'https://callback.test/reconcile',?,true,?,?)",site.value(),secret,at,at);
        var s=service(false,false,ProviderState.Status.SUCCEEDED);s.reconcile(site,p.id(),"request");s.reconcile(site,p.id(),"repeat");
        assertThat(count("outbox_events")).isEqualTo(1);assertThat(jdbc.queryForObject("select id from outbox_events",UUID.class)).isEqualTo(events.findByPayment(site,p.id()).getFirst().id());assertThat(jdbc.queryForObject("select secret_version_id from outbox_events",UUID.class)).isEqualTo(secret);
    }
    @Test void createdAndUnknownAreSuccessfulNoopsWhilePendingAdvances() {
        var p=persisted();var a=attemptSave.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"attempt","CARD",INITIAL_TIME).unknown("UNKNOWN",INITIAL_TIME),INITIAL_TIME);
        service(false,false,ProviderState.Status.CREATED).reconcile(site,p.id(),"created");service(false,false,ProviderState.Status.UNKNOWN).reconcile(site,p.id(),"unknown");
        assertThat(count("payment_events")).isZero();assertThat(attempts.findById(site,a.value().id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(service(false,false,ProviderState.Status.PENDING).reconcile(site,p.id(),"pending").status()).isEqualTo(PaymentStatus.PROCESSING);assertThat(attempts.findById(site,a.value().id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.PROCESSING);
    }
}
