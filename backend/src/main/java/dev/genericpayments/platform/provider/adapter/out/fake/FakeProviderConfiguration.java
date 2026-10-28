package dev.genericpayments.platform.provider.adapter.out.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "payment.provider", havingValue = "fake")
class FakeProviderConfiguration {
    @Bean
    FakeSubscriptionStateAdapter fakeSubscriptionState(org.springframework.jdbc.core.JdbcTemplate jdbc,java.time.Clock clock,
            tools.jackson.databind.ObjectMapper mapper,org.springframework.transaction.PlatformTransactionManager transactions,
            org.springframework.core.env.Environment environment) {
        return new FakeSubscriptionStateAdapter(jdbc,clock,mapper,transactions,environment.matchesProfiles("local"));
    }
    @Bean
    FakeSubscriptionProviderAdapter fakeSubscriptionProvider(FakeSubscriptionStateAdapter state) {
        return new FakeSubscriptionProviderAdapter(state);
    }
    @Bean
    dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry subscriptionProviderRegistry(FakeSubscriptionProviderAdapter fake) {
        return account -> {FakeSubscriptionStateAdapter.requireFake(account);return fake;};
    }
    @Bean
    FakeCatalogProviderAdapter catalogProviderPort() {
        return new FakeCatalogProviderAdapter();
    }
}
