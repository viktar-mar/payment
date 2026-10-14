package dev.genericpayments.platform.customers.adapter.in.configuration;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CustomerEmailOtpPropertiesTest {
    private final ApplicationContextRunner binding=new ApplicationContextRunner()
        .withUserConfiguration(BindingConfiguration.class);

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void bindsEveryOutboxSettingIncludingExplicitWorkerControl(boolean enabled) {
        binding.withPropertyValues(
            "payment.customer-email-otp.outbox.batch-size=7",
            "payment.customer-email-otp.outbox.lease=PT45S",
            "payment.customer-email-otp.outbox.max-attempts=7",
            "payment.customer-email-otp.outbox.maximum-backoff=PT2M",
            "payment.customer-email-otp.outbox.terminal-retention=P3D",
            "payment.customer-email-otp.outbox.cleanup-batch=19",
            "payment.customer-email-otp.outbox.worker-enabled="+enabled,
            "payment.customer-email-otp.outbox.delay=PT0.25S",
            "payment.customer-email-otp.outbox.challenge-retention=PT36H",
            "payment.customer-email-otp.outbox.challenge-cleanup-batch=23"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            var policy=context.getBean(dev.genericpayments.platform.customers.application.port.out.EmailOtpPolicyPort.class);
            assertThat(policy.mailBatchSize()).isEqualTo(7);
            assertThat(policy.mailLease()).isEqualTo(Duration.ofSeconds(45));
            assertThat(policy.mailMaxAttempts()).isEqualTo(7);
            assertThat(policy.mailMaximumBackoff()).isEqualTo(Duration.ofMinutes(2));
            assertThat(policy.mailTerminalRetention()).isEqualTo(Duration.ofDays(3));
            assertThat(policy.mailCleanupBatch()).isEqualTo(19);
            assertThat(policy.mailWorkerEnabled()).isEqualTo(enabled);
            assertThat(policy.mailWorkerDelay()).isEqualTo(Duration.ofMillis(250));
            assertThat(policy.challengeRetention()).isEqualTo(Duration.ofHours(36));
            assertThat(policy.challengeCleanupBatch()).isEqualTo(23);
        });
    }

    @ParameterizedTest
    @MethodSource("invalidBoundOutboxSettings")
    void bindingRejectsOutOfRangeOutboxValuesInsteadOfFallingBack(String setting) {
        binding.withPropertyValues("payment.customer-email-otp.outbox."+setting)
            .run(context -> assertThat(context).hasFailed());
    }

    static Stream<String> invalidBoundOutboxSettings() {
        return Stream.of("batch-size=0","lease=PT1S","max-attempts=9","maximum-backoff=PT25H",
            "terminal-retention=PT30M","cleanup-batch=0","delay=PT0.05S",
            "challenge-retention=PT30M","challenge-cleanup-batch=0");
    }

    @Test void compatibilityConstructorRetainsDefaultsForNewerSettings() {
        var outbox=new CustomerEmailOtpProperties.Outbox(7,Duration.ofSeconds(45),7,Duration.ofMinutes(2),
            Duration.ofDays(3),19);
        assertThat(outbox.batchSize()).isEqualTo(7);
        assertThat(outbox.lease()).isEqualTo(Duration.ofSeconds(45));
        assertThat(outbox.maxAttempts()).isEqualTo(7);
        assertThat(outbox.maximumBackoff()).isEqualTo(Duration.ofMinutes(2));
        assertThat(outbox.terminalRetention()).isEqualTo(Duration.ofDays(3));
        assertThat(outbox.cleanupBatch()).isEqualTo(19);
        assertThat(outbox.workerEnabled()).isTrue();
        assertThat(outbox.delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(outbox.challengeRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(outbox.challengeCleanupBatch()).isEqualTo(1000);
    }

    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(CustomerEmailOtpProperties.class)
    static class BindingConfiguration {}

    @Test void appliesDocumentedDefaultsOnlyWhenValuesAreAbsent() {
        var properties=new CustomerEmailOtpProperties(null,null,null,null,null,null,null,null);
        assertThat(properties.ttl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.maxAttempts()).isEqualTo(5);
        assertThat(properties.resendCooldown()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.rateLimit().siteRequests()).isEqualTo(60);
        assertThat(properties.rateLimit().emailRequests()).isEqualTo(5);
        assertThat(properties.rateLimit().sourceIpRequests()).isEqualTo(10);
        assertThat(properties.mailBatchSize()).isEqualTo(50);
        assertThat(properties.mailLease()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.mailMaxAttempts()).isEqualTo(8);
        assertThat(properties.mailMaximumBackoff()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.mailTerminalRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.challengeRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.challengeCleanupBatch()).isEqualTo(1000);
    }

    @Test void rejectsZeroNegativeAndOperationallyAbsurdLimits() {
        assertThatThrownBy(() -> properties(0,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(-1,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(6,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(null,new CustomerEmailOtpProperties.RateLimit(0,5,10,Duration.ofMinutes(10),1000),null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(null,new CustomerEmailOtpProperties.RateLimit(60,10_001,10,Duration.ofMinutes(10),1000),null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(null,null,new CustomerEmailOtpProperties.Outbox(0,Duration.ofSeconds(60),8,
            Duration.ofMinutes(30),Duration.ofDays(7),1000))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(null,null,new CustomerEmailOtpProperties.Outbox(50,Duration.ofSeconds(60),9,
            Duration.ofMinutes(30),Duration.ofDays(7),1000))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(null,null,new CustomerEmailOtpProperties.Outbox(50,Duration.ofSeconds(60),8,
            Duration.ofMinutes(30),Duration.ofMinutes(59),1000))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void productionSmtpRequiresTlsCredentialsAndExactSenderReference() {
        var absent=new CustomerEmailOtpProperties.Smtp("smtp.example.test",587,"","","sender","no-reply@example.test",
            true,false,2000,5000,5000);
        assertThat(absent.configured(false)).isFalse();
        assertThat(absent.configured(true)).isTrue();
        assertThatThrownBy(() -> new CustomerEmailOtpProperties.Smtp("smtp.example.test",587,"user","secret","sender",
            "no-reply@example.test",true,true,2000,5000,5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerEmailOtpProperties.Smtp("smtp.example.test",587,"user","secret","sender",
            "no-reply@example.test",true,false,0,5000,5000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void heartbeatLeaseDoesNotDependOnAnArbitrarySumOfSmtpIoTimeouts() {
        var outbox=new CustomerEmailOtpProperties.Outbox(50,Duration.ofSeconds(5),8,Duration.ofMinutes(30),
            Duration.ofDays(7),1000);
        var smtp=new CustomerEmailOtpProperties.Smtp("smtp.example.test",587,"user","secret","sender",
            "no-reply@example.test",true,false,30_000,60_000,60_000);

        assertThatCode(() -> new CustomerEmailOtpProperties(null,null,null,null,null,outbox,smtp,null))
            .doesNotThrowAnyException();
    }

    @Test void templatesAreBoundedAndRejectUnknownPlaceholders() {
        assertThatThrownBy(() -> new CustomerEmailOtpProperties(null,null,null,null,null,null,null,
            Map.of("otp-broken",new CustomerEmailOtpProperties.Template("OTP","Code {{unknown}}"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerEmailOtpProperties(null,null,null,null,null,null,null,
            Map.of("otp-no-code",new CustomerEmailOtpProperties.Template("Sign in","Expires in {{ttl_minutes}} minutes"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Mail template must render an OTP code");
        var configured=new CustomerEmailOtpProperties(null,null,null,null,null,null,null,
            Map.of("otp-default",new CustomerEmailOtpProperties.Template("OTP {{code}}","Expires {{ttl_minutes}}")));
        assertThat(configured.templates()).containsOnlyKeys("otp-default");
    }

    @Test void trustedProxyListIsBoundedAndImmutable() {
        var source=new java.util.ArrayList<>(List.of("192.0.2.0/24"));
        var properties=new CustomerEmailOtpProperties(null,null,null,null,source,null,null,null);
        source.clear();
        assertThat(properties.trustedProxyCidrs()).containsExactly("192.0.2.0/24");
        assertThatThrownBy(() -> new CustomerEmailOtpProperties(null,null,null,null,
            java.util.Collections.nCopies(101,"192.0.2.0/24"),null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static CustomerEmailOtpProperties properties(Integer attempts,CustomerEmailOtpProperties.RateLimit rate,
            CustomerEmailOtpProperties.Outbox outbox) {
        return new CustomerEmailOtpProperties(null,attempts,null,rate,null,outbox,null,null);
    }
}
