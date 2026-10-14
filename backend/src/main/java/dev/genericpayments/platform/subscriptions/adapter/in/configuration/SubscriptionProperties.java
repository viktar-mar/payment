package dev.genericpayments.platform.subscriptions.adapter.in.configuration;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public record SubscriptionProperties(int batchSize,Duration lease,Duration delay) implements dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionWorkerConfigurationPort {
    public SubscriptionProperties {
        if(batchSize<1||batchSize>100||lease==null||lease.compareTo(Duration.ofSeconds(1))<0||lease.compareTo(Duration.ofSeconds(600))>0||delay==null||delay.isZero()||delay.isNegative())throw new IllegalArgumentException("Invalid subscription worker configuration");
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SubscriptionProperties(@Value("${payment.subscriptions.batch-size:20}")int batch,@Value("${payment.subscriptions.lease-seconds:30}")long lease,@Value("${payment.subscriptions.delay-ms:30000}")long delay){this(batch,Duration.ofSeconds(lease),Duration.ofMillis(delay));}
    public Duration backoff(int attempts){return Duration.ofSeconds(attempts<=0?60:attempts==1?300:attempts==2?1800:7200);}
}
