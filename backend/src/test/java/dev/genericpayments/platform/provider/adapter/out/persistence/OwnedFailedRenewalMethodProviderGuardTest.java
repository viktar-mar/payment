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
