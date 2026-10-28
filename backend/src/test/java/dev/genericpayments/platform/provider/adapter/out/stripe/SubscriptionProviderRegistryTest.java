package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.in.configuration.SubscriptionProviderRegistryConfiguration;
import dev.genericpayments.platform.provider.adapter.out.fake.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeSubscriptionProviderAdapter;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SubscriptionProviderRegistryTest {
    final AtomicInteger constructed=new AtomicInteger();
    final FakeSubscriptionProviderAdapter fake=new FakeSubscriptionProviderAdapter(mock(FakeSubscriptionStateAdapter.class));
    final StripeSubscriptionProviderAdapter stripe=mock(StripeSubscriptionProviderAdapter.class);
    ApplicationContextRunner runner(boolean enabled){return new ApplicationContextRunner().withUserConfiguration(SubscriptionProviderRegistryConfiguration.class)
        .withBean(FakeSubscriptionProviderAdapter.class,()->fake)
        .withBean(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSetupContinuationGuard.class,()->mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSetupContinuationGuard.class))
        .withBean(dev.genericpayments.platform.subscriptions.application.service.SubscriptionResumeReceiptTransaction.class,()->mock(dev.genericpayments.platform.subscriptions.application.service.SubscriptionResumeReceiptTransaction.class))
        .withBean(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.class,()->mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.class))
        .withBean(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCancellationProofStore.class,()->mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCancellationProofStore.class))
        .withBean(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionRecoveryProofStore.class,()->mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionRecoveryProofStore.class))
        .withBean(StripeSandboxProperties.class,()->enabled?new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)):new StripeSandboxProperties(false,"","",""))
        .withBean(StripeSubscriptionProviderAdapter.class,()->{constructed.incrementAndGet();return stripe;},definition->definition.setLazyInit(true));}
    // Catches eager Stripe construction/key lookup on the default Fake path.
    @Test void disabledStripeNeedsNoKeysOrStripeConstruction(){runner(false).run(context->{assertThat(context).hasNotFailed().hasSingleBean(SubscriptionProviderRegistry.class);var registry=context.getBean(SubscriptionProviderRegistry.class);
        assertThat(registry.forAccount(StripeSandboxConfigurationTest.account(PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"config-ref"))).isSameAs(fake);
        assertThatThrownBy(()->registry.forAccount(StripeSandboxConfigurationTest.account(PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"stripe-sandbox-default"))).isInstanceOf(RuntimeException.class);assertThat(constructed).hasValue(0);});}
    @Test void mixedAccountsRouteExactlyByProvider(){runner(true).run(context->{assertThat(context).hasNotFailed().hasSingleBean(SubscriptionProviderRegistry.class);var registry=context.getBean(SubscriptionProviderRegistry.class);
        assertThat(registry.forAccount(StripeSandboxConfigurationTest.account(PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"config-ref"))).isSameAs(fake);
        assertThat(registry.forAccount(StripeSandboxConfigurationTest.account(PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"stripe-sandbox-default"))).isSameAs(stripe);assertThat(constructed).hasValue(1);});}
    @Test void invalidAccountsNeverReachEitherProvider(){runner(true).run(context->{assertThat(context).hasSingleBean(SubscriptionProviderRegistry.class);var registry=context.getBean(SubscriptionProviderRegistry.class);
        assertThatThrownBy(()->registry.forAccount(null)).isInstanceOf(RuntimeException.class);
        var stripeAccount=StripeSandboxConfigurationTest.account(PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"stripe-sandbox-default");
        assertThatThrownBy(()->registry.forAccount(StripeSandboxConfigurationTest.account(PaymentProviderType.STRIPE,PaymentAccountMode.LIVE,"stripe-sandbox-default"))).isInstanceOf(RuntimeException.class);assertThat(constructed).hasValue(0);});}
    // Registry selection must preserve historical reads; dispatch separately enforces active ownership.
    @Test void inactiveAccountsStillResolveTheirOriginalProviderForSafeViews(){runner(true).run(context->{var registry=context.getBean(SubscriptionProviderRegistry.class);assertThatCode(()->{
        assertThat(registry.forAccount(StripeSandboxConfigurationTest.account(PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"ref").disable())).isSameAs(fake);
        assertThat(registry.forAccount(StripeSandboxConfigurationTest.account(PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"stripe-sandbox-default").disable())).isSameAs(stripe);
    }).doesNotThrowAnyException();});}
}
