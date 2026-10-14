package dev.genericpayments.platform.provider.adapter.out.fake;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FakePaymentConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(FakePaymentConfiguration.class)
        .withBean(FakeProviderStatePersistenceAdapter.class, () -> mock(FakeProviderStatePersistenceAdapter.class));

    @Test void defaultIsManualAndExplicitTrueEnablesAutomaticSuccess() {
        runner.run(c -> assertThat(c.getBean(FakePaymentProperties.class).autoSuccess()).isFalse());
        runner.withPropertyValues("payment.fake.auto-success=true").run(c ->
            assertThat(c.getBean(FakePaymentProperties.class).autoSuccess()).isTrue());
    }

    @Test void malformedBooleanFailsConfigurationBinding() {
        runner.withPropertyValues("payment.fake.auto-success=unexpected").run(c -> assertThat(c).hasFailed());
    }
}
