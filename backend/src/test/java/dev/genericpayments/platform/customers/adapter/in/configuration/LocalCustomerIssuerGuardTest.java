package dev.genericpayments.platform.customers.adapter.in.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

class LocalCustomerIssuerGuardTest {
    @Configuration(proxyBeanMethods=false)
    @ComponentScan("dev.genericpayments.platform.customers.adapter.in.localfixture")
    static class Scan {
        @org.springframework.context.annotation.Bean java.time.Clock clock(){return java.time.Clock.systemUTC();}
    }
    @Test void fixtureConfigurationIsRejectedOutsideExclusivelyLocalOrTestProfiles() {
        for(String profiles:new String[]{"prod","local,prod",""})
            new ApplicationContextRunner().withUserConfiguration(Scan.class)
                .withPropertyValues("spring.profiles.active="+profiles,"payment.customer-local-issuer.enabled=true")
                .run(context -> {assertThat(context).as("production rejects fixture signing configuration").hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("Local Customer issuer fixtures require exclusively local/test profiles");});
    }
    @Test void disabledFixtureHasNoSigningEndpoint() {
        new ApplicationContextRunner().withUserConfiguration(Scan.class).withPropertyValues("spring.profiles.active=prod")
            .run(context -> assertThat(context).doesNotHaveBean("localCustomerIssuerController"));
    }
    @Test void productionAlsoRejectsExplicitEnvironmentStyleFixtureConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(Scan.class)
            .withPropertyValues("spring.profiles.active=prod","PAYMENT_CUSTOMER_LOCAL_ISSUER_ENABLED=true")
            .run(context -> {assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasRootCauseMessage("Local Customer issuer fixtures require exclusively local/test profiles");});
    }
}
