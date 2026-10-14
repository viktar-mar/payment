package dev.genericpayments.platform.customers.adapter.in.web;

import static org.assertj.core.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CustomerAuthenticationRateLimitPropertiesTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withUserConfiguration(BindingConfiguration.class);

    @ParameterizedTest(name="rejects {0}")
    @MethodSource("outOfRangeSettings")
    void configurationBindingFailsInsteadOfClampingOutOfRangeLimits(String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest(name="accepts {0}")
    @MethodSource("boundarySettings")
    void documentedUpperBoundsRemainValid(String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasNotFailed());
    }

    static Stream<String> outOfRangeSettings() {
        return Stream.of(
            "payment.customer-authentication.rate-limit.oidc-begin-per-minute=0",
            "payment.customer-authentication.rate-limit.oidc-begin-per-minute=-1",
            "payment.customer-authentication.rate-limit.oidc-begin-per-minute=10001",
            "payment.customer-authentication.rate-limit.oidc-exchange-per-minute=0",
            "payment.customer-authentication.rate-limit.oidc-exchange-per-minute=-1",
            "payment.customer-authentication.rate-limit.oidc-exchange-per-minute=10001",
            "payment.customer-authentication.rate-limit.site-token-exchange-per-minute=0",
            "payment.customer-authentication.rate-limit.site-token-exchange-per-minute=-1",
            "payment.customer-authentication.rate-limit.site-token-exchange-per-minute=10001",
            "payment.customer-authentication.rate-limit.site-token-link-proof-per-minute=0",
            "payment.customer-authentication.rate-limit.site-token-link-proof-per-minute=-1",
            "payment.customer-authentication.rate-limit.site-token-link-proof-per-minute=10001",
            "payment.customer-authentication.rate-limit.max-keys=0",
            "payment.customer-authentication.rate-limit.max-keys=-1",
            "payment.customer-authentication.rate-limit.max-keys=100001");
    }

    static Stream<String> boundarySettings() {
        return Stream.of(
            "payment.customer-authentication.rate-limit.oidc-begin-per-minute=10000",
            "payment.customer-authentication.rate-limit.oidc-exchange-per-minute=10000",
            "payment.customer-authentication.rate-limit.site-token-exchange-per-minute=10000",
            "payment.customer-authentication.rate-limit.site-token-link-proof-per-minute=10000",
            "payment.customer-authentication.rate-limit.max-keys=100000");
    }

    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(CustomerAuthenticationRateLimitProperties.class)
    static class BindingConfiguration {}
}
