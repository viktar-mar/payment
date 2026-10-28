package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort;
import dev.genericpayments.platform.webhooks.application.service.SubscriptionInboxWorker;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Caller-order characterization: an unchanged read must carry the sequence captured before IO. */
class SubscriptionObservationCallerTest {
    @ParameterizedTest @CsvSource({"RECONCILE,STRIPE","RECONCILE,FAKE","INBOX,STRIPE","INBOX,FAKE"})
    void capturesBeforeBlockingRetrieveAndCarriesSameFenceWithoutTouchingFake(String caller,PaymentProviderType providerType)throws Exception{
        var now=Instant.parse("2026-10-28T10:00:00Z");var s=SubscriptionFixtures.active(now.minusSeconds(100),now.plusSeconds(100));
        var account=PaymentAccount.create(s.paymentAccountId(),"observation",providerType,PaymentAccountMode.SANDBOX,"acct_fixture","fixture");
        var subscriptions=mock(SubscriptionStore.class);var operations=mock(SubscriptionOperationStore.class);var accounts=mock(LoadPaymentAccountPort.class);
        when(subscriptions.find(s.siteId(),s.id())).thenReturn(Optional.of(s));when(subscriptions.findOwned(account.id(),s.id())).thenReturn(Optional.of(s));when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        var reads=new SubscriptionReadPreparation(operations,mock(TrialEligibilityStore.class));var telemetry=mock(SubscriptionTelemetryPort.class);
        var captures=new AtomicInteger();var sequence=new AtomicInteger(17);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        SubscriptionObservationPort observations=new SubscriptionObservationPort(){
            public long captureFence(Subscription subscription){assertThat(subscription).isEqualTo(s);captures.incrementAndGet();return sequence.get();}
            public void consumeFence(Subscription subscription,long value){throw new AssertionError("Caller must leave transactional consumption to FactService");}
            public boolean acceptRevision(Subscription subscription,long value){throw new AssertionError("Caller must leave revision acceptance to FactService");}
        };
        var observed=new Snapshot(s.providerReference(),s.billingStatus(),s.billingPrice(),s.currentPeriod(),s.trial(),null,List.of(),null,false,providerType==PaymentProviderType.STRIPE?0:1,now);
        var provider=mock(SubscriptionProviderPort.class);
        when(provider.retrieve(any())).thenAnswer(call->{assertThat(captures).hasValue(providerType==PaymentProviderType.STRIPE?1:0);entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Test did not release retrieve");return observed;});
        var applied=new CopyOnWriteArrayList<ApplySubscriptionSnapshotUseCase.Command>();
        ApplySubscriptionSnapshotUseCase facts=command->{assertThat(command.snapshot()).isSameAs(observed);assertThat(command.fence().revision()).isEqualTo(s.revision());assertThat(command.fence().observationSequence()).isEqualTo(providerType==PaymentProviderType.STRIPE?17L:null);applied.add(command);};
        SubscriptionTransactionPort tx=new SubscriptionTransactionPort(){public <T>T required(Supplier<T> action){return action.get();}public <T>T requiresNew(Supplier<T> action){return action.get();}public boolean active(){return false;}};
        Runnable run;
        if(caller.equals("RECONCILE")){
            var sweep=mock(SubscriptionSweepPort.class);var properties=mock(SubscriptionWorkerConfigurationPort.class);when(properties.delay()).thenReturn(Duration.ofSeconds(30));when(properties.backoff(anyInt())).thenReturn(Duration.ofSeconds(30));
            var claim=new SubscriptionSweepPort.Claim(s.siteId(),s.id(),"owned",UUID.randomUUID(),s.revision());when(sweep.advanceRevision(eq(claim),eq(s.revision()),any())).thenReturn(claim);
            var service=new SubscriptionReconciliationService(subscriptions,operations,tx,mock(SubscriptionAuthorityPort.class),accounts,a->provider,facts,reads,sweep,telemetry,Clock.fixed(now,ZoneOffset.UTC),properties,observations);
            run=()->service.reconcileClaim(claim);
        }else{
            var inbox=mock(SubscriptionInboxPort.class);var objects=mock(SubscriptionObjectPort.class);var claim=new SubscriptionInboxPort.Claim(UUID.randomUUID(),"owned");
            when(inbox.claim(anyString(),eq(20),any(),any())).thenReturn(List.of(claim));
            when(inbox.lock(eq(claim),any())).thenReturn(new SubscriptionProviderEnvelope(providerType,account.id(),"evt_owned",s.providerReference(),"SUBSCRIPTION_CHANGED",now,now));
            when(objects.resolve(providerType,account.id(),SubscriptionObjectPort.Kind.SUBSCRIPTION,s.providerReference())).thenReturn(Optional.of(s.id()));
            run=new SubscriptionInboxWorker(inbox,tx,objects,subscriptions,operations,accounts,a->provider,facts,Clock.fixed(now,ZoneOffset.UTC),20,30,reads,telemetry,observations)::runOnce;
        }
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var task=executor.submit(run);try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(applied).isEmpty();assertThat(sequence).hasValue(17);assertThat(captures).hasValue(providerType==PaymentProviderType.STRIPE?1:0);}finally{release.countDown();}
            task.get(5,TimeUnit.SECONDS);
        }
        assertThat(applied).hasSize(1);assertThat(sequence).hasValue(17);assertThat(captures).hasValue(providerType==PaymentProviderType.STRIPE?1:0);
    }
}
