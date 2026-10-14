package dev.genericpayments.platform.payments.adapter.in.configuration;

import dev.genericpayments.platform.payments.application.port.out.IdempotencyPort;
import dev.genericpayments.platform.payments.application.service.IdempotencyService;
import dev.genericpayments.platform.shared.adapter.out.observability.MicrometerStage2TelemetryAdapter;
import dev.genericpayments.platform.shared.adapter.out.observability.PendingOutboxGaugeRefreshAdapter;
import dev.genericpayments.platform.shared.application.port.out.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@EnableConfigurationProperties(IdempotencyConfiguration.TelemetryProperties.class)
public class IdempotencyConfiguration {
    @ConfigurationProperties("payment.telemetry")
    public record TelemetryProperties(Set<String> siteKeys) {
        public TelemetryProperties { siteKeys=siteKeys == null ? Set.of() : Set.copyOf(siteKeys); }
    }
    @Bean Stage2TelemetryPort stage2Telemetry(MeterRegistry registry,TelemetryProperties properties) {
        return new MicrometerStage2TelemetryAdapter(registry,properties.siteKeys());
    }
    @Bean PendingOutboxGaugeRefreshAdapter pendingOutboxTelemetry(org.springframework.jdbc.core.JdbcTemplate jdbc,
        Stage2TelemetryPort telemetry,TelemetryProperties properties,org.springframework.transaction.PlatformTransactionManager transactions) {
        return new PendingOutboxGaugeRefreshAdapter(jdbc,telemetry,properties.siteKeys(),transactions);
    }
    @Bean IdempotencyService idempotencyService(IdempotencyPort records,EncryptedSecretPort encryption,
        Stage2TelemetryPort telemetry,DigestPort digest,Clock clock) {
        return new IdempotencyService(records,encryption,telemetry,digest,clock);
    }
}
