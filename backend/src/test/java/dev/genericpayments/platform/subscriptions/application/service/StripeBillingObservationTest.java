package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.Site;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Pure fact-service behavior. Database CAS/rollback is separately exercised in hosted integration tests. */
class StripeBillingObservationTest {
    static final Instant NOW=Instant.parse("2026-10-01T00:00:00Z");
    final Map<Class<?>,Object> dependencies=new HashMap<>();
    final MemoryObservations observations=new MemoryObservations();
    Subscription current;PaymentAccount account;SubscriptionFactService service;int saves,events;
    @BeforeEach void setup() throws Exception {
        current=SubscriptionFixtures.active(NOW.minusSeconds(86400),NOW.plusSeconds(86400));
        account=PaymentAccount.create(current.paymentAccountId(),"billing",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_test","stripe-sandbox-default");
        var constructor=SubscriptionFactService.class.getConstructors()[0];
        for(var type:constructor.getParameterTypes())dependencies.put(type,type==Clock.class?Clock.fixed(NOW,ZoneOffset.UTC):type==SubscriptionObservationPort.class?observations:mock(type));
        service=(SubscriptionFactService)constructor.newInstance(Arrays.stream(constructor.getParameterTypes()).map(dependencies::get).toArray());
        when(dep(SubscriptionStore.class).findOwned(any(),any())).thenAnswer(i->Optional.of(current));
        when(dep(SubscriptionStore.class).lock(any(),any())).thenAnswer(i->current);
        when(dep(SubscriptionStore.class).save(any(),anyLong())).thenAnswer(i->{current=i.getArgument(0);saves++;return current;});
        when(dep(LockPaymentAccountPort.class).findByIdForUpdate(any())).thenAnswer(i->Optional.of(account));
        when(dep(LockSitePort.class).findByIdForUpdate(any())).thenReturn(Optional.of(mock(Site.class)));
        when(dep(CustomerStore.class).findByIdForUpdate(any(),any())).thenReturn(Optional.of(Customer.create(current.customerId(),current.siteId(),null,null,NOW)));
        when(dep(SubscriptionCatalogPort.class).terms(any(),any())).thenAnswer(i->current.billingPrice());
        when(dep(SubscriptionEventStore.class).append(any())).thenAnswer(i->{events++;return true;});
    }
    <T>T dep(Class<T> type){return type.cast(dependencies.get(type));}
    Snapshot snapshot(Subscription.Status status,long revision){return new Snapshot(current.providerReference(),status,current.billingPrice(),current.currentPeriod(),null,null,List.of(),null,false,revision,NOW);}
    Fence fence(){return new Fence(current.revision(),null,null,null,null,observations.captureFence(current));}
    void apply(Snapshot snapshot,Fence fence){service.apply(new ApplySubscriptionSnapshotUseCase.Command(account,current.id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,"test","test",fence));}
    // Removing the mandatory Stripe fence enables the compatibility constructor to bypass all read ownership.
    @Test void stripeCompatibilityConstructorCannotBypassReadFence(){assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,0),null)).isInstanceOf(IllegalStateException.class);assertThat(saves).isZero();}
    @Test void stripePositiveRevisionCannotPoisonZeroReads(){assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,99),fence())).isInstanceOf(IllegalStateException.class);assertThat(observations.sequence).isZero();}
    @Test void oldFiveArgumentFenceCannotBypassSequence(){assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,0),new Fence(current.revision(),null,null,null,null))).isInstanceOf(IllegalStateException.class);}
    // Catches consuming lifecycle revision instead of the private read sequence and accepting a reversed no-op response.
    @Test void newerNoOpConsumesReadOnceWithoutInvalidatingLiveQuote(){
        var before=current;var captured=fence();var delayed=snapshot(Subscription.Status.PAUSED,0);
        var q=new SubscriptionQuote(UUID.randomUUID(),current.siteId(),current.id(),"test",current.billingPrice().priceId(),1,current.billingPrice(),ChangeTiming.NEXT_PERIOD,0,1000,"EUR",current.currentPeriod().end(),NOW.plusSeconds(300),true,current.revision(),0,"quote");
        apply(snapshot(Subscription.Status.ACTIVE,0),captured);
        assertThat(observations.sequence).isEqualTo(1);assertThat(current).isEqualTo(before);assertThat(saves).isZero();assertThat(events).isZero();
        assertThatCode(()->PlanChangeRules.requireQuoteCurrent(q,current.revision(),0,NOW)).doesNotThrowAnyException();
        assertThatThrownBy(()->apply(delayed,captured)).isInstanceOf(IllegalStateException.class).hasMessage("OBSERVATION_STALE");assertThat(current).isEqualTo(before);
    }
    @Test void changedLocalRevisionStillRejectsMatchingObservationSequence(){var f=new Fence(current.revision()-1,null,null,null,null,0L);assertThatThrownBy(()->apply(snapshot(Subscription.Status.PAUSED,0),f)).hasMessage("OBSERVATION_STALE");assertThat(observations.sequence).isZero();}
    @Test void foreignOperationDoesNotConsumeSequence(){var f=new Fence(current.revision(),UUID.randomUUID(),0L,null,null,0L);assertThatThrownBy(()->apply(snapshot(Subscription.Status.PAUSED,0),f)).hasMessage("OBSERVATION_STALE");assertThat(observations.sequence).isZero();}
    @Test void fakeEqualAndOlderRevisionsKeepOriginalProjectionRules(){
        account=PaymentAccount.create(current.paymentAccountId(),"fake",PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"fake","fake");
        apply(snapshot(Subscription.Status.ACTIVE,10),null);apply(snapshot(Subscription.Status.ACTIVE,10),null);apply(snapshot(Subscription.Status.PAUSED,9),null);
        assertThat(current.billingStatus()).isEqualTo(Subscription.Status.ACTIVE);assertThat(saves).isZero();assertThat(observations.sequence).isZero();
        apply(snapshot(Subscription.Status.PAUSED,11),null);assertThat(current.billingStatus()).isEqualTo(Subscription.Status.PAUSED);
    }
    static class MemoryObservations implements SubscriptionObservationPort {
        long sequence;long provider=-1;
        public long captureFence(Subscription s){return sequence;}
        public void consumeFence(Subscription s,long expected){if(expected!=sequence)throw new IllegalStateException("OBSERVATION_STALE");sequence++;}
        public boolean acceptRevision(Subscription s,long revision){if(revision<provider)return false;provider=revision;return true;}
    }
}
