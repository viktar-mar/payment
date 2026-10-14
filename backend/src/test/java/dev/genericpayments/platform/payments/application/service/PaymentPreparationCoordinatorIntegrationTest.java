package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.adapter.out.observability.MicrometerStage2TelemetryAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PaymentPreparationCoordinatorIntegrationTest extends ProviderTestFixture {
    @Test void unconfiguredMetricSiteFailsBeforeLocalCreation() {
        var coordinator = new PaymentPreparationCoordinator(creation, new PaymentProviderRegistry(Map.of(account.provider(), fake)),
            new MicrometerStage2TelemetryAdapter(new SimpleMeterRegistry(), Set.of()), () -> 0);
        assertThatThrownBy(() -> coordinator.prepare(prepare(payment()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payments")).isZero(); assertThat(count("payment_events")).isZero(); assertThat(count("fake_provider_operations")).isZero();
    }
    @Test void delayedPreparationCannotRegressAnAlreadyConfirmedPayment() {
        var p=payment();var command=prepare(p);creation.createLocal(command);var result=fake.preparePayment(request(p));
        var confirmation=confirm(p);attemptPhase.startAttempt(confirmation);
        attemptPhase.recordProviderAcknowledgementOrUnknown(confirmation,new ProviderResult.Accepted(result.reference(),true));
        assertThatThrownBy(() -> creation.recordPreparedOrUnknown(command,result)).hasMessage("PAYMENT_STATE_CONFLICT");
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(events.findByPayment(site,p.id())).extracting(PaymentEvent::type).containsExactly(PaymentEventType.PAYMENT_CREATED,PaymentEventType.ATTEMPT_CREATED,PaymentEventType.PAYMENT_PROCESSING);
    }
    @Test void creationAndJournalRollbackTogetherAndEmitNoTelemetry() {
        var meters=new SimpleMeterRegistry();
        var c=new PaymentPreparationCoordinator(creation,new PaymentProviderRegistry(Map.of(account.provider(),probe(false))),new MicrometerStage2TelemetryAdapter(meters,Set.of("test-site")),() -> 0);
        var p=payment();rejectJournal(PaymentEventType.PAYMENT_CREATED);
        assertThatThrownBy(() -> c.prepare(prepare(p))).isInstanceOf(RuntimeException.class);
        assertThat(load.findById(site,p.id())).isEmpty();assertThat(count("payment_events")).isZero();
        assertThat(count("fake_provider_objects")).isZero();assertThat(meters.getMeters()).isEmpty();
    }
    @Test void acknowledgementRollsBackButRetryReusesCommittedProviderReceipt() {
        var meters=new SimpleMeterRegistry();
        var c=new PaymentPreparationCoordinator(creation,new PaymentProviderRegistry(Map.of(account.provider(),probe(false))),new MicrometerStage2TelemetryAdapter(meters,Set.of("test-site")),() -> 0);
        var p=payment();rejectJournal(PaymentEventType.PAYMENT_METHOD_REQUIRED);
        assertThatThrownBy(() -> c.prepare(prepare(p))).isInstanceOf(RuntimeException.class);
        var stored=load.findById(site,p.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.CREATED);assertThat(stored.providerPaymentReference()).isNull();
        assertThat(count("payment_events")).isEqualTo(1);assertThat(count("fake_provider_operations")).isEqualTo(1);
        assertThat(meters.find("payment.transition").counter()).isNull();
        assertThat(meters.find("payment.provider.call").timer()).isNull();
        removeJournalFailure();assertThat(c.prepare(prepare(p)).status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(count("fake_provider_operations")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(2);
    }
    @Test void forgedSiteTelemetryAndAccountRoutingFailBeforeAnyWriteOrCall() {
        var p=payment();var c=new PaymentPreparationCoordinator(creation,new PaymentProviderRegistry(Map.of(account.provider(),probe(false))),new MicrometerStage2TelemetryAdapter(new SimpleMeterRegistry(),Set.of("test-site")),() -> 0);
        assertThatThrownBy(() -> c.prepare(new PreparePaymentCommand(p,account,"other-site","r","i"))).isInstanceOf(IllegalArgumentException.class);
        var altered=new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount(account.id(),account.key(),account.provider(),account.mode(),account.status(),"another-routing",account.credentialsRef());
        assertThatThrownBy(() -> c.prepare(new PreparePaymentCommand(p,altered,"test-site","r","i"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payments")).isZero();assertThat(count("payment_events")).isZero();assertThat(count("fake_provider_objects")).isZero();
    }
    @Test void preparationCommitsBothFactsAndCallsProviderOutsideTransaction() {
        var meters=new SimpleMeterRegistry();var ticks=new AtomicLong();
        var coordinator=new PaymentPreparationCoordinator(creation,new PaymentProviderRegistry(Map.of(account.provider(),probe(false))),new MicrometerStage2TelemetryAdapter(meters,Set.of("test-site")),() -> ticks.getAndAdd(250_000_000));
        var p=payment(); var result=coordinator.prepare(prepare(p));
        assertThat(result.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(events.findByPayment(site,p.id())).extracting(PaymentEvent::type).containsExactly(PaymentEventType.PAYMENT_CREATED,PaymentEventType.PAYMENT_METHOD_REQUIRED);
        assertThat(coordinator.prepare(prepare(p)).id()).isEqualTo(p.id());
        assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(2);
        assertThat(meters.get("payment.created").counter().count()).isEqualTo(1);
        assertThat(meters.get("payment.transition").counter().count()).isEqualTo(1);
        assertThat(meters.get("payment.provider.call").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(500);
        assertThat(meters.get("payment.provider.call").timer().getId().getTags()).extracting(io.micrometer.core.instrument.Tag::toString).containsExactly("tag(operation=prepare)","tag(provider=fake)","tag(result=accepted)","tag(site=test-site)");
    }
    @Test void thrownProviderCallIsUnknownAndDurableWithoutActiveTransaction() {
        var meters=new SimpleMeterRegistry(); var coordinator=new PaymentPreparationCoordinator(creation,new PaymentProviderRegistry(Map.of(account.provider(),probe(true))),new MicrometerStage2TelemetryAdapter(meters,Set.of("test-site")),() -> 0L);
        var p=payment();assertThat(coordinator.prepare(prepare(p)).status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(events.findByPayment(site,p.id())).extracting(PaymentEvent::type).containsExactly(PaymentEventType.PAYMENT_CREATED,PaymentEventType.PROVIDER_RESULT_UNKNOWN);
        assertThat(meters.get("payment.provider.unknown").counter().count()).isEqualTo(1);
    }
    PaymentProviderPort probe(boolean failure) {
        return new DelegatingProvider(fake) {
            @Override public ProviderResult preparePayment(ProviderPaymentRequest r) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(count("payments")).isEqualTo(1);
                if(failure) throw new IllegalStateException("unsafe upstream text");
                return super.preparePayment(r);
            }
        };
    }
}
