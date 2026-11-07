package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.time.*;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Real proof adapters, inert store and clock; no transaction or network infrastructure. */
class OwnedFailedRenewalMethodFactProofTest {
    final Instant utc=Instant.parse("2026-11-01T12:00:00Z"),billing=utc.plusSeconds(1000);
    final Subscription s=SubscriptionFixtures.active(utc,utc.plusSeconds(2000));
    final PaymentAccount account=PaymentAccount.create(s.paymentAccountId(),"owned",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_owned","alias");
    final SubscriptionBillingTimePort.Sample sample=new SubscriptionBillingTimePort.Sample(billing,SubscriptionBillingTimePort.Mode.ISOLATED);
    final Command command=new Command(account,s.id(),null,SubscriptionEvent.Source.RECONCILIATION,"proof","test",new Fence(s.revision(),UUID.randomUUID(),0L,null,null,0L),sample);
    boolean active=true,reject;SubscriptionBillingTimePort.Sample actual=sample;List<String> calls=new ArrayList<>();
    @Test void explicitlySelectedAdaptersDelegateOnlyTheirOwnPhaseInCallerTransaction() {
        method().requireAuthorized(command,s,List.of(),billing,utc);
        recovery().requireAuthorized(command,s,List.of(),billing,utc);
        assertThat(calls).containsExactly("requireMethodFactInTransaction","requireRecoveryFactInTransaction");
    }
    @Test void driftMissingExpectedModeAndMissingTransactionFailBeforeStore() {
        for(var successor:List.of(false,true))for(var bad:List.of("time","mode","missing","transaction")) {
            actual=bad.equals("time")?new SubscriptionBillingTimePort.Sample(billing.plusSeconds(1),sample.mode()):bad.equals("mode")?new SubscriptionBillingTimePort.Sample(billing,SubscriptionBillingTimePort.Mode.ORDINARY):sample;
            active=!bad.equals("transaction");var c=bad.equals("missing")?new Command(account,s.id(),null,command.source(),"proof","test",command.fence()):command;
            assertThatThrownBy(()->{if(successor)recovery().requireAuthorized(c,s,List.of(),billing,utc);else method().requireAuthorized(c,s,List.of(),billing,utc);}).hasMessage("ISOLATED_BILLING_UNSUPPORTED");
        }
        assertThat(calls).isEmpty();
    }
    @Test void durableRejectionDoesNotTryTheOtherPhase() {
        reject=true;
        assertThatThrownBy(()->method().requireAuthorized(command,s,List.of(),billing,utc)).hasMessage("durable rejection");
        assertThat(calls).containsExactly("requireMethodFactInTransaction");calls.clear();
        assertThatThrownBy(()->recovery().requireAuthorized(command,s,List.of(),billing,utc)).hasMessage("durable rejection");
        assertThat(calls).containsExactly("requireRecoveryFactInTransaction");
    }
    @Test void defaultMethodProofIsDenied(){assertThatThrownBy(()->SubscriptionIsolatedMethodProofPort.denied().requireAuthorized(command,s,List.of(),billing,utc)).hasMessage("ISOLATED_BILLING_UNSUPPORTED");}
    OwnedFailedRenewalMethodFactProof method(){return new OwnedFailedRenewalMethodFactProof(store(),tx(),time());}
    OwnedFailedRenewalMethodRecoveryFactProof recovery(){return new OwnedFailedRenewalMethodRecoveryFactProof(store(),tx(),time());}
    OwnedFailedRenewalMethodStore store(){return (OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},(p,m,a)->{assertThat(active).isTrue();assertThat(a).containsExactly(command,s,List.of(),billing,utc);calls.add(m.getName());if(reject)throw new IllegalStateException("durable rejection");return null;});}
    SubscriptionTransactionPort tx(){return new SubscriptionTransactionPort(){public boolean active(){return active;}public <T>T required(Supplier<T> work){throw new AssertionError("must not start transaction");}public <T>T requiresNew(Supplier<T> work){throw new AssertionError("must not start transaction");}};}
    SubscriptionBillingTimePort time(){return new SubscriptionBillingTimePort(){public Sample sample(PaymentAccountId account,dev.genericpayments.platform.sites.domain.SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer,Instant at){assertThat(at).isEqualTo(utc);assertThat(account).isEqualTo(s.paymentAccountId());assertThat(site).isEqualTo(s.siteId());assertThat(customer).isEqualTo(s.customerId());return actual;}public void requireUnscopedAllowed(dev.genericpayments.platform.sites.domain.SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer){throw new AssertionError();}};}
}
