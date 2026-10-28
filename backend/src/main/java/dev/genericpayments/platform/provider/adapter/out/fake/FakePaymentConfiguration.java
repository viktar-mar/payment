package dev.genericpayments.platform.provider.adapter.out.fake;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.payments.application.port.out.PaymentProviderRegistry;
import org.springframework.beans.factory.ObjectProvider;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripePaymentProviderAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties;
import dev.genericpayments.platform.payments.application.port.out.PaymentProviderPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Payment routing is account-specific, independent of the legacy catalog provider switch. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FakePaymentProperties.class)
class FakePaymentConfiguration {
    @Bean
    FakePaymentProviderAdapter fakePaymentProvider(FakeProviderStatePersistenceAdapter state, FakePaymentProperties properties) {
        return new FakePaymentProviderAdapter(state, properties);
    }

    @Bean
    PaymentProviderRegistry paymentProviderRegistry(FakePaymentProviderAdapter fake,
            ObjectProvider<StripePaymentProviderAdapter> stripe, ObjectProvider<StripeSandboxProperties> properties) {
        var providers = new java.util.EnumMap<PaymentProviderType, PaymentProviderPort>(PaymentProviderType.class);
        providers.put(PaymentProviderType.FAKE, fake);
        var settings = properties.getIfAvailable();
        if (settings != null && settings.enabled()) providers.put(PaymentProviderType.STRIPE, stripe.getObject());
        return new PaymentProviderRegistry(providers);
    }
}
