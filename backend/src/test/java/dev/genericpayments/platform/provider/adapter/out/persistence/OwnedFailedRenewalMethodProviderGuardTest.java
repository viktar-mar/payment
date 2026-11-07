package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** No DataSource/context: verifies closed defaults and early typed admission, not SQL behavior. */
class OwnedFailedRenewalMethodProviderGuardTest {
    private final java.time.Instant dispatchAt=java.time.Instant.parse("2026-11-01T12:00:00Z");
    private PaymentAccount dispatchAccount() {
        return PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"owned-account",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_owned","alias");
    }
    private ProviderOperation dispatchClaim(PaymentAccount a,java.time.Instant retryBefore,java.time.Instant leaseUntil,long version) {
        return new ProviderOperation(UUID.randomUUID(),a.id(),a.externalAccountId(),a.credentialsRef(),ProviderOperation.Kind.SETUP_INTENT_CREATE,
            UUID.randomUUID(),"subscription:owned:SETUP:SETUP","fingerprint",ProviderOperation.Status.DISPATCHING,null,dispatchAt,
            retryBefore,"original-claimant",leaseUntil,version);
    }
    @Test void dispatchUsesFreshPostAuthenticationTimeEvenWhenSuppliedTimeWasOriginallyValid() {
        var a=dispatchAccount();var claim=dispatchClaim(a,dispatchAt.plusSeconds(82800),dispatchAt.plusSeconds(60),1);
        for(var actual:java.util.List.of(dispatchAt.plusSeconds(60),dispatchAt.plusSeconds(61))) {
            var trace=new java.util.ArrayList<String>();
            assertThatThrownBy(()->JdbcProviderOperationStore.requireOwnedMethodDispatchAfterAuthentication(a,claim,dispatchAt.plusSeconds(1),
                ()->{trace.add("receipt locked");trace.add("resource reauthenticated");return claim;},
                ()->{assertThat(trace).containsExactly("receipt locked","resource reauthenticated");trace.add("fresh database UTC");return actual;}))
                .isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_RESULT_UNKNOWN"));
            assertThat(trace).containsExactly("receipt locked","resource reauthenticated","fresh database UTC");
        }
    }
    @Test void dispatchSamplesActualTimeOnlyAfterAllAuthenticationAndAcceptsALiveExactClaim() {
        var a=dispatchAccount();var claim=dispatchClaim(a,dispatchAt.plusSeconds(82800),dispatchAt.plusSeconds(60),1);
        var authenticated=new java.util.concurrent.atomic.AtomicBoolean();var samples=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(JdbcProviderOperationStore.requireOwnedMethodDispatchAfterAuthentication(a,claim,dispatchAt.plusSeconds(1),
            ()->{authenticated.set(true);return claim;},()->{
                assertThat(authenticated).isTrue();samples.incrementAndGet();return dispatchAt.plusSeconds(59);
            })).isEqualTo(claim);
        assertThat(samples).hasValue(1);
    }
    @Test void freshDispatchTimeMustAlsoRespectRetryAndFirstDispatchBounds() {
        var a=dispatchAccount();var supplied=dispatchAt.plusSeconds(1);
        // A longer lease cannot extend either independent exclusive retry/window boundary.
        var retry=dispatchClaim(a,dispatchAt.plusSeconds(30),dispatchAt.plusSeconds(100),1);
        var firstWindow=dispatchClaim(a,dispatchAt.plusSeconds(90000),dispatchAt.plusSeconds(90000),1);
        assertThatThrownBy(()->JdbcProviderOperationStore.requireOwnedMethodDispatchAfterAuthentication(a,retry,supplied,()->retry,()->dispatchAt.plusSeconds(30)))
            .isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->JdbcProviderOperationStore.requireOwnedMethodDispatchAfterAuthentication(a,firstWindow,supplied,()->firstWindow,()->dispatchAt.plusSeconds(82800)))
            .isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->JdbcProviderOperationStore.requireOwnedMethodDispatchAfterAuthentication(a,retry,supplied,()->retry,()->dispatchAt.minusNanos(1)))
            .isInstanceOf(PaymentApplicationException.class);
    }
    @Test void dispatchBoundaryStillRequiresTheExactOriginalVersionOneReceipt() {
        var a=dispatchAccount();
        for(long version:new long[]{0,2}) {
            var claim=dispatchClaim(a,dispatchAt.plusSeconds(82800),dispatchAt.plusSeconds(60),version);
            assertThatThrownBy(()->JdbcProviderOperationStore.requireOwnedMethodDispatchAfterAuthentication(a,claim,dispatchAt.plusSeconds(1),()->claim,()->dispatchAt.plusSeconds(2)))
                .isInstanceOf(PaymentApplicationException.class);
        }
        var claim=dispatchClaim(a,dispatchAt.plusSeconds(82800),dispatchAt.plusSeconds(60),1);
        var different=dispatchClaim(a,dispatchAt.plusSeconds(82800),dispatchAt.plusSeconds(60),1);
        assertThatThrownBy(()->JdbcProviderOperationStore.requireOwnedMethodDispatchAfterAuthentication(a,claim,dispatchAt.plusSeconds(1),()->different,()->dispatchAt.plusSeconds(2)))
            .isInstanceOf(PaymentApplicationException.class);
    }
    @Test void recoveredCashDoesNotClaimAnUnobservedMethodReadinessFlag() {
        var id=UUID.randomUUID();var at=java.time.Instant.parse("2026-11-01T12:00:00Z");
        var operation=new dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation(id,
            new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),new dev.genericpayments.platform.customers.domain.CustomerId(UUID.randomUUID()),UUID.randomUUID(),
            dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Kind.RECOVER,
            dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.RESERVED,"owned","digest","v1.recover.test","subscription:owned:RECOVER",2,1,"sub_owned",null,at,at,0);
        var terms=new dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms(
            new dev.genericpayments.platform.catalog.domain.PriceId(UUID.randomUUID()),"monthly",1,
            new dev.genericpayments.platform.catalog.domain.ProductId(UUID.randomUUID()),dev.genericpayments.platform.catalog.domain.Money.of(1000,"EUR"),
            dev.genericpayments.platform.catalog.domain.BillingInterval.MONTH,0,dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,dev.genericpayments.platform.catalog.domain.TaxMode.NONE);
        var snapshot=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot("sub_owned",
            dev.genericpayments.platform.subscriptions.domain.Subscription.Status.ACTIVE,terms,null,null,null,java.util.List.of(),operation.providerOperationKey(),false,0,at,
            new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.OperationObservation(operation.providerOperationKey(),
                dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.OperationState.APPLIED));
        assertThatCode(()->JdbcOwnedFailedRenewalMethodStore.requireOperationObservation(snapshot,operation,
            dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Phase.RECOVERED)).doesNotThrowAnyException();
        assertThatThrownBy(()->JdbcOwnedFailedRenewalMethodStore.requireOperationObservation(snapshot,operation,
            dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Phase.METHOD)).isInstanceOf(IllegalStateException.class);
    }
    @Test void olderStoreImplementationsHaveNoImplicitMethodReservationOrDispatchAuthority() {
        var old=(ProviderOperationStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ProviderOperationStore.class},
            (proxy,method,args)->{if(method.isDefault())return InvocationHandler.invokeDefault(proxy,method,args);throw new AssertionError("Unexpected ordinary fallback");});
        assertThatThrownBy(()->old.reserveOwnedFailedRenewalMethodStep(null,null,ProviderOperation.Kind.SETUP_INTENT_CREATE))
            .isInstanceOf(UnsupportedOperationException.class).hasMessage("Owned failed renewal method reservation is unavailable");
        assertThatThrownBy(()->old.requireOwnedFailedRenewalMethodDispatch(null,null,null,null))
            .isInstanceOf(UnsupportedOperationException.class).hasMessage("Owned failed renewal method dispatch is unavailable");
    }
    @Test void typedJdbcSeamsRejectMissingDurableAuthorityBeforeAnySqlOrNetworkAccess() {
        var store=new JdbcProviderOperationStore(new JdbcTemplate());
        var account=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"owned-account",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_owned","alias");
        for(var kind:ProviderOperation.Kind.values())assertThatThrownBy(()->store.reserveOwnedFailedRenewalMethodStep(account,null,kind))
            .isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->store.requireOwnedFailedRenewalMethodDispatch(account,null,null,null)).isInstanceOf(PaymentApplicationException.class);
    }
}
