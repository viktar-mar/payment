package dev.genericpayments.platform.webhooks.adapter.in.configuration;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CallbackConfigurationTest {
    @Test void spoofedLocalPropertiesCannotRelaxDefaultProfile() {
        var environment=new MockEnvironment().withProperty("payment.callbacks.local-host-allowlist","localhost,example.com");
        var policy=new CallbackConfigurationBeans().callbackUrlPolicy(environment);
        assertThatThrownBy(()->policy.validate("http://localhost:8081/callback")).isInstanceOf(RuntimeException.class);
        environment.setActiveProfiles("local");
        assertThat(new CallbackConfigurationBeans().callbackUrlPolicy(environment).validate("http://localhost:8081/callback").getHost()).isEqualTo("localhost");
    }
}
