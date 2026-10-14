package dev.genericpayments.platform.payments.adapter.in.configuration;
import dev.genericpayments.platform.payments.application.service.ProviderInboxWorker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="payment.inbox.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class ProviderInboxScheduler {
    private final ObjectProvider<ProviderInboxWorker> worker;
    public ProviderInboxScheduler(ObjectProvider<ProviderInboxWorker> worker,@Value("${payment.inbox.delay-ms:1000}")long delay){if(delay<100||delay>60000)throw new IllegalArgumentException("Invalid inbox delay");this.worker=worker;}
    @Scheduled(fixedDelayString="${payment.inbox.delay-ms:1000}",initialDelayString="${payment.inbox.delay-ms:1000}")public void run(){worker.getObject().runOnce();}
}
