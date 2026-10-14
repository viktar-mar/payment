package dev.genericpayments.platform.webhooks.adapter.in.configuration;
import dev.genericpayments.platform.webhooks.application.service.OutboxWorker;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="payment.callbacks.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class OutboxScheduler {
    private final ObjectProvider<OutboxWorker> worker;private final CallbackConfigurationPort configs;private final Clock clock;
    public OutboxScheduler(ObjectProvider<OutboxWorker> worker,CallbackConfigurationPort configs,Clock clock,@Value("${payment.callbacks.delay-ms:1000}")long delay){if(delay<100||delay>60000)throw new IllegalArgumentException("Invalid callback delay");this.worker=worker;this.configs=configs;this.clock=clock;}
    @Scheduled(fixedDelayString="${payment.callbacks.delay-ms:1000}",initialDelayString="${payment.callbacks.delay-ms:1000}")public void run(){worker.getObject().runOnce();}
    @Scheduled(cron="${payment.callbacks.cleanup-cron:0 0 4 * * *}")public void cleanup(){configs.cleanup(clock.instant());}
}
