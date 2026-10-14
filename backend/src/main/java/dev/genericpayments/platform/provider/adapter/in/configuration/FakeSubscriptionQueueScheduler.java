package dev.genericpayments.platform.provider.adapter.in.configuration;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeSubscriptionQueueRelay;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="payment.inbox.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class FakeSubscriptionQueueScheduler {
    private final FakeSubscriptionQueueRelay relay;
    public FakeSubscriptionQueueScheduler(FakeSubscriptionQueueRelay relay,@Value("${payment.inbox.delay-ms:1000}")long delay) {
        if(delay<100||delay>60000)throw new IllegalArgumentException("Invalid inbox delay");this.relay=relay;
    }
    @Scheduled(fixedDelayString="${payment.inbox.delay-ms:1000}",initialDelayString="${payment.inbox.delay-ms:1000}")
    public void run() {relay.runOnce();}
}
