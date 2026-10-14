package dev.genericpayments.platform.donations.adapter.out.observability;

import dev.genericpayments.platform.donations.application.port.out.DonationTelemetryPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class MicrometerDonationTelemetryAdapter implements DonationTelemetryPort {
    private final MeterRegistry registry;
    public MicrometerDonationTelemetryAdapter(MeterRegistry registry){this.registry=Objects.requireNonNull(registry);}
    public void checkout(CheckoutOutcome outcome,Duration duration){
        String label=Objects.requireNonNull(outcome).name().toLowerCase(Locale.ROOT);
        registry.counter("donation.checkout","outcome",label).increment();
        registry.timer("donation.checkout.latency","outcome",label).record(duration.isNegative()?Duration.ZERO:duration);
    }
    public void redaction(RedactionOutcome outcome){registry.counter("donation.message.redaction","outcome",Objects.requireNonNull(outcome).name().toLowerCase(Locale.ROOT)).increment();}
}
