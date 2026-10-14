package dev.genericpayments.platform.customers.adapter.out.observability;

import dev.genericpayments.platform.customers.application.port.out.CustomerTelemetryPort;
import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
public final class MicrometerCustomerTelemetryAdapter implements CustomerTelemetryPort {
    private final MeterRegistry registry;
    public MicrometerCustomerTelemetryAdapter(MeterRegistry registry,JdbcTemplate jdbc) {
        this.registry=Objects.requireNonNull(registry);
        // Two global aggregate gauges; no per-Site/Customer/issuer allocation or identifying labels.
        Gauge.builder("customer.mail.pending",jdbc,db -> count(db,"PENDING")).register(registry);
        Gauge.builder("customer.mail.active",jdbc,db -> count(db,"PROCESSING")).register(registry);
    }
    private static double count(JdbcTemplate jdbc,String status) {
        Long count=jdbc.queryForObject("select count(*) from customer_mail_outbox where status=?",Long.class,status);
        return count==null?0:count.doubleValue();
    }
    private static String label(Enum<?> value){return Objects.requireNonNull(value).name().toLowerCase(Locale.ROOT);}
    @Override public void authExchange(Mode mode,Outcome outcome,Duration duration) {
        Objects.requireNonNull(duration);
        if(duration.isNegative())duration=Duration.ZERO;
        var tags=Tags.of("mode",label(mode),"outcome",label(outcome));
        registry.counter("customer.auth.exchange",tags).increment();
        registry.timer("customer.auth.latency",tags).record(duration);
    }
    @Override public void otp(OtpOutcome outcome){registry.counter("customer.otp","outcome",label(outcome)).increment();}
    @Override public void mail(MailOutcome outcome){registry.counter("customer.mail.delivery","mail_outcome",label(outcome)).increment();}
    @Override public void session(SessionOperation operation){registry.counter("customer.session","operation",label(operation)).increment();}
    @Override public void link(Outcome outcome){registry.counter("customer.identity.link","outcome",label(outcome)).increment();}
    @Override public void anonymization(Outcome outcome){registry.counter("customer.anonymization","outcome",label(outcome)).increment();}
}
