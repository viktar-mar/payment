package dev.genericpayments.platform.provider.adapter.out.fake;

import dev.genericpayments.platform.catalog.application.port.out.CatalogProviderPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FakeProviderConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(FakeProviderConfiguration.class)
        .withBean(org.springframework.jdbc.core.JdbcTemplate.class,()->org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
        .withBean(java.time.Clock.class,java.time.Clock::systemUTC)
        .withBean(tools.jackson.databind.ObjectMapper.class,tools.jackson.databind.ObjectMapper::new)
        .withBean(org.springframework.transaction.PlatformTransactionManager.class,()->org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));

    @Test
    void createsTheFakeProviderWhenConfiguredForFake() {
        contextRunner.withPropertyValues("payment.provider=fake").run(context ->
            org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(CatalogProviderPort.class));
    }

    @Test
    void createsNoProviderWhenThePropertyIsAbsent() {
        contextRunner.run(context ->
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(CatalogProviderPort.class));
    }

    @Test
    void createsNoProviderForANonFakeProvider() {
        contextRunner.withPropertyValues("payment.provider=stripe").run(context ->
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(CatalogProviderPort.class));
    }
}
