package dev.genericpayments.platform.provider.adapter.out.fake;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.payments.application.port.out.PaymentProviderRegistry;
import java.util.Map;
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
    PaymentProviderRegistry paymentProviderRegistry(FakePaymentProviderAdapter fake) {
        return new PaymentProviderRegistry(Map.of(PaymentProviderType.FAKE, fake));
    }
}
