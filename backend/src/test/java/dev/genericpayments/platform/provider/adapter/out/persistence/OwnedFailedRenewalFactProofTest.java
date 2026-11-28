package dev.genericpayments.platform.provider.adapter.out.persistence;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalStore;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import java.time.*;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
/** All ports inert; no JDBC object, context, network or extensions. */
class OwnedFailedRenewalFactProofTest {
    final Instant utc=Instant.parse("2026-11-01T12:00:00Z"),billing=utc.plusSeconds(1000);
    final Subscription s=SubscriptionFixtures.active(utc,utc.plusSeconds(2000));
    final PaymentAccount account=PaymentAccount.create(s.paymentAccountId(),"owned",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_owned","alias");
    final Command command=new Command(account,s.id(),null,SubscriptionEvent.Source.RECONCILIATION,"owned-failed-renewal:proof","test",new Fence(s.revision(),UUID.randomUUID(),0L,null,null,0L));
    final List<SubscriptionOperation> operations=List.of();
    boolean active=true,isolated=true,drift=false;int storeCalls,samples;
    @Test void exactOwnerIsolatedSampleDelegatesCurrentProofInCallerTransaction(){proof().requireAuthorized(command,s,operations,billing,utc);assertThat(storeCalls).isEqualTo(1);assertThat(samples).isEqualTo(1);}
    @Test void ordinaryFallbackChangedSampleAndMissingTransactionCannotReachStore(){
        isolated=false;reject();isolated=true;drift=true;reject();drift=false;active=false;reject();assertThat(storeCalls).isZero();
    }
    @Test void storeRejectionCannotBecomeAuthorization(){var proof=new OwnedFailedRenewalFactProof(store(true),transaction(),time());assertThatThrownBy(()->proof.requireAuthorized(command,s,operations,billing,utc)).hasMessage("forged or expired");}
    private void reject(){assertThatThrownBy(()->proof().requireAuthorized(command,s,operations,billing,utc)).isInstanceOf(IllegalStateException.class);}
    private OwnedFailedRenewalFactProof proof(){return new OwnedFailedRenewalFactProof(store(false),transaction(),time());}
    private SubscriptionTransactionPort transaction(){return new SubscriptionTransactionPort(){public boolean active(){return active;}public <T>T required(Supplier<T> work){throw new AssertionError("must not start transaction");}public <T>T requiresNew(Supplier<T> work){throw new AssertionError("must not start transaction");}};}
    private SubscriptionBillingTimePort time(){return new SubscriptionBillingTimePort(){public Sample sample(PaymentAccountId a,SiteId site,CustomerId customer,Instant at){samples++;assertThat(a).isEqualTo(s.paymentAccountId());assertThat(site).isEqualTo(s.siteId());assertThat(customer).isEqualTo(s.customerId());assertThat(at).isEqualTo(utc);return new Sample(drift?billing.plusSeconds(1):billing,isolated?Mode.ISOLATED:Mode.ORDINARY);}public void requireUnscopedAllowed(SiteId site,CustomerId customer){throw new AssertionError();}};}
    private OwnedFailedRenewalStore store(boolean reject){return (OwnedFailedRenewalStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalStore.class},(proxy,method,args)->{assertThat(method.getName()).isEqualTo("requireRecoveryFactInTransaction");assertThat(active).isTrue();assertThat(args).containsExactly(command,s,operations,billing,utc);storeCalls++;if(reject)throw new IllegalStateException("forged or expired");return null;});}
}
