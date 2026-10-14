package dev.genericpayments.platform.subscriptions.adapter.out.observability;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTelemetryPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;
@Component
public class MicrometerSubscriptionTelemetryAdapter implements SubscriptionTelemetryPort {
    private final MeterRegistry registry;
    public MicrometerSubscriptionTelemetryAdapter(MeterRegistry registry){this.registry=registry;}
    public void record(Operation operation,Outcome outcome){
        io.micrometer.core.instrument.Counter.builder("subscription.operations").tag("operation",operation.name()).tag("outcome",outcome.name()).register(registry).increment();
    }
    public void observeUnknownAge(Duration age){observe("subscription.unknown.age",age);}
    public void observeInboxLag(Duration lag){observe("subscription.inbox.lag",lag);}
    private void observe(String name,Duration duration){
        io.micrometer.core.instrument.Timer.builder(name).register(registry).record(duration.isNegative()?Duration.ZERO:duration);
    }
}
