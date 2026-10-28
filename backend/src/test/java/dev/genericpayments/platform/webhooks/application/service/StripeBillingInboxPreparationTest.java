package dev.genericpayments.platform.webhooks.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class StripeBillingInboxPreparationTest {
    final Instant now=Instant.parse("2026-10-28T12:00:00Z");
    final Subscription seed=SubscriptionFixtures.active(now.minusSeconds(20),now.plusSeconds(50));
    final Subscription prepared=new Subscription(seed.id(),seed.siteId(),seed.customerId(),seed.productId(),seed.paymentAccountId(),seed.billingPrice(),seed.entitledPrice(),seed.billingStatus(),"sub_recovered",seed.currentPeriod(),seed.paidThrough(),null,null,null,null,null,seed.revision()+1,seed.createdAt(),now,null);
    final PaymentAccount account=PaymentAccount.create(seed.paymentAccountId(),"prepared",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_owned","stripe-sandbox-default");
    final SubscriptionInboxPort inbox=mock(SubscriptionInboxPort.class);
    final SubscriptionObjectPort objects=mock(SubscriptionObjectPort.class);
    final SubscriptionStore subscriptions=mock(SubscriptionStore.class);
    final SubscriptionOperationStore operations=mock(SubscriptionOperationStore.class);
    final LoadPaymentAccountPort accounts=mock(LoadPaymentAccountPort.class);
    final SubscriptionInboxResolutionPort resolution=mock(SubscriptionInboxResolutionPort.class);
    final PrepareSubscriptionReadUseCase reads=mock(PrepareSubscriptionReadUseCase.class);
    final SubscriptionObservationPort observations=mock(SubscriptionObservationPort.class);
    final SubscriptionProviderPort provider=mock(SubscriptionProviderPort.class);
    final ApplySubscriptionSnapshotUseCase facts=mock(ApplySubscriptionSnapshotUseCase.class);
    final SubscriptionInboxPort.Claim claim=new SubscriptionInboxPort.Claim(UUID.randomUUID(),"live");
    final SubscriptionProviderEnvelope envelope=new SubscriptionProviderEnvelope(account.provider(),account.id(),"evt_original","sub_recovered","SUBSCRIPTION_CHANGED",now,now);
    final SubscriptionTransactionPort tx=new SubscriptionTransactionPort(){public <T>T required(Supplier<T>s){return s.get();}public <T>T requiresNew(Supplier<T>s){return s.get();}public boolean active(){return false;}};
    void run(){new SubscriptionInboxWorker(inbox,tx,objects,subscriptions,operations,accounts,a->provider,facts,Clock.fixed(now,ZoneOffset.UTC),20,30,reads,mock(SubscriptionTelemetryPort.class),observations,resolution).runOnce();}
    void setup(){when(inbox.claim(anyString(),anyInt(),any(),any())).thenReturn(List.of(claim));when(inbox.lock(eq(claim),any())).thenReturn(envelope);when(accounts.findById(account.id())).thenReturn(Optional.of(account));when(subscriptions.findOwned(account.id(),seed.id())).thenReturn(Optional.of(prepared));when(resolution.resolve(envelope)).thenReturn(Optional.of(seed.id()));when(observations.captureFence(prepared)).thenReturn(7L);when(provider.retrieve(any())).thenReturn(new Snapshot("sub_recovered",prepared.billingStatus(),prepared.billingPrice(),prepared.currentPeriod(),null,null,List.of(),null,false,0,now));}
    @Test void recoveredPreparationFinishesBeforeReloadAndFenceCapture(){setup();run();var order=inOrder(resolution,subscriptions,reads,observations,provider,facts);order.verify(resolution).resolve(envelope);order.verify(subscriptions).findOwned(account.id(),seed.id());order.verify(reads).financial(prepared);order.verify(observations).captureFence(prepared);order.verify(provider).retrieve(argThat(l->"sub_recovered".equals(l.reference())));order.verify(facts).apply(argThat(c->c.fence().revision()==prepared.revision()&&c.fence().observationSequence()==7));verify(inbox).complete(claim);}
    @Test void lostInboxLeaseAfterFreshRetrieveCannotApplyOrComplete(){setup();when(inbox.lock(eq(claim),any())).thenReturn(envelope).thenThrow(new IllegalStateException("CLAIM_LOST"));run();verify(provider).retrieve(any());verifyNoInteractions(facts);verify(inbox,never()).complete(any());verify(inbox).fail(eq(claim),eq("PROCESSING_FAILED"),any());}
    @Test void stagedScheduleRemainsTypedAndUnresolved(){setup();var schedule=new SubscriptionProviderEnvelope(account.provider(),account.id(),"evt_schedule","sub_sched_owned","SUBSCRIPTION_SCHEDULE_CHANGED",now,now);when(inbox.lock(eq(claim),any())).thenReturn(schedule);run();verify(objects).resolve(account.provider(),account.id(),SubscriptionObjectPort.Kind.SCHEDULE,"sub_sched_owned");verify(resolution).resolve(schedule);verify(inbox).fail(eq(claim),eq("UNRESOLVED_OBJECT"),any());verifyNoInteractions(provider,facts,observations);}
}
