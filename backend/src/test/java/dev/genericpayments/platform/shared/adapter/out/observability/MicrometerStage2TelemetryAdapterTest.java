package dev.genericpayments.platform.shared.adapter.out.observability;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerStage2TelemetryAdapterTest {
    @Test void exactReplayConflictCountersUseOnlyBoundedOperationAndOutcome() {
        var registry=new SimpleMeterRegistry(); var telemetry=new MicrometerStage2TelemetryAdapter(registry,Set.of("demo"));
        telemetry.idempotencyReplay(Operation.CHECKOUT_CREATE,ReplayState.IN_PROGRESS);
        telemetry.idempotencyReplay(Operation.CHECKOUT_CREATE,ReplayState.COMPLETED);
        telemetry.idempotencyReplay(Operation.CHECKOUT_CREATE,ReplayState.COMPLETED);
        telemetry.idempotencyConflict(Operation.CHECKOUT_CREATE);
        assertThat(registry.get("payment.idempotency.replay").tags("operation","checkout_create","result","in_progress").counter().count()).isEqualTo(1);
        assertThat(registry.get("payment.idempotency.replay").tags("operation","checkout_create","result","completed").counter().count()).isEqualTo(2);
        assertThat(registry.get("payment.idempotency.conflict").tag("operation","checkout_create").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag ->
            assertThat(tag.getKey()).isIn("operation","result")));
    }

    @Test void providerDurationIsUnitSafeAndUnconfiguredSiteCannotBecomeALabel() {
        var registry=new SimpleMeterRegistry(); var telemetry=new MicrometerStage2TelemetryAdapter(registry,Set.of("demo"));
        var context=new Context("demo",Provider.FAKE);
        telemetry.providerCall(context,ProviderOperation.CONFIRM,ProviderResult.UNKNOWN,Duration.ofMillis(250));
        assertThat(registry.get("payment.provider.call").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250);
        assertThat(registry.get("payment.provider.unknown").counter().count()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() -> telemetry.paymentCreated(new Context("secret-or-payment-id",Provider.FAKE)));
        assertThat(registry.getMeters()).allSatisfy(m -> assertThat(m.getId().toString()).doesNotContain("secret-or-payment-id"));
        assertThatIllegalArgumentException().isThrownBy(() -> telemetry.providerCall(context,ProviderOperation.CONFIRM,ProviderResult.ACCEPTED,Duration.ofMillis(-1)));
    }

    @Test void allFutureFamiliesExposeFixedNamesAndBoundedTags() {
        var registry=new SimpleMeterRegistry(); var telemetry=new MicrometerStage2TelemetryAdapter(registry,Set.of("demo"));
        var context=new Context("demo",Provider.FAKE);
        telemetry.paymentCreated(context);
        telemetry.paymentTransition(context,PaymentState.CREATED,PaymentState.PROCESSING);
        for(var outcome:InboxOutcome.values()) telemetry.inbox(context,outcome);
        telemetry.inboxLag(context,Duration.ofSeconds(3));
        telemetry.outboxPending(context,5); telemetry.outboxPending(context,2);
        for(var outcome:OutboxOutcome.values()) telemetry.outbox(context,outcome);
        telemetry.outboxLag(context,Duration.ofSeconds(4));
        telemetry.refundRequested(context); telemetry.refundResult(context,RefundResult.SUCCEEDED);
        assertThat(registry.get("payment.outbox.pending").gauge().value()).isEqualTo(2);
        assertThat(registry.getMeters()).extracting(m -> m.getId().getName()).contains(
            "payment.created","payment.transition","payment.inbox.received","payment.inbox.duplicate",
            "payment.inbox.processed","payment.inbox.failed","payment.inbox.lag","payment.outbox.pending",
            "payment.outbox.delivered","payment.outbox.retried","payment.outbox.dead","payment.outbox.lag",
            "payment.refund.requested","payment.refund.result");
    }
}
