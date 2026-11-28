package dev.genericpayments.platform.webhooks.application.service;

import static org.junit.jupiter.api.Assertions.*;
import static dev.genericpayments.platform.support.StripeBillingInertSdkFixture.port;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Pure worker regression: no extensions, parent, mocks, Spring context or external resources. */
class StripeBillingMappedInboxFenceTest {
    @Test void preexistingStripeMappingStillResolvesAndFailureNeverProjectsOrCompletes(){
        var f=new StripeBillingInertSdkFixture();var now=Instant.parse("2026-10-29T12:00:00Z");var id=UUID.randomUUID();
        var claim=new SubscriptionInboxPort.Claim(UUID.randomUUID(),"owned");
        var envelope=new SubscriptionProviderEnvelope(f.account.provider(),f.account.id(),"evt_owned","sub_owned","SUBSCRIPTION_CHANGED",now,now);
        var calls=new ArrayList<String>();
        var inbox=port(SubscriptionInboxPort.class,m->{calls.add(m.name());return switch(m.name()){
            case "claim"->List.of(claim);case "lock"->envelope;case "fail"->{assertEquals("PROCESSING_FAILED",m.args()[1]);yield null;}
            default->throw new AssertionError("Unexpected inbox effect "+m.name());};});
        var objects=port(SubscriptionObjectPort.class,m->{calls.add("mapped");return Optional.of(id);});
        var resolution=port(SubscriptionInboxResolutionPort.class,m->{calls.add("resolved");throw new IllegalStateException("owned clock refused");});
        var subscriptions=port(SubscriptionStore.class,m->{throw new AssertionError("lifecycle read reached");});
        var operations=port(SubscriptionOperationStore.class,m->{throw new AssertionError("operation read reached");});
        var facts=port(ApplySubscriptionSnapshotUseCase.class,m->{throw new AssertionError("financial projection reached");});
        var reads=port(PrepareSubscriptionReadUseCase.class,m->{throw new AssertionError("preparation reached");});
        var telemetry=port(SubscriptionTelemetryPort.class,m->{throw new AssertionError("success telemetry reached");});
        var observations=port(SubscriptionObservationPort.class,m->{throw new AssertionError("observation reached");});
        var tx=new SubscriptionTransactionPort(){public<T>T required(Supplier<T> work){return work.get();}public<T>T requiresNew(Supplier<T> work){return work.get();}public boolean active(){return false;}};
        new SubscriptionInboxWorker(inbox,tx,objects,subscriptions,operations,f.accounts,a->{throw new AssertionError("provider retrieval reached");},facts,Clock.fixed(now,ZoneOffset.UTC),20,30,reads,telemetry,observations,resolution).runOnce();
        assertEquals(List.of("claim","lock","mapped","resolved","fail"),calls);assertTrue(f.http.calls.isEmpty());
    }
}
