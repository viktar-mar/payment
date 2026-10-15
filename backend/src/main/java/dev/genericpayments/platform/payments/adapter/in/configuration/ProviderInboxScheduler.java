package dev.genericpayments.platform.payments.adapter.in.configuration;
import dev.genericpayments.platform.payments.application.service.ProviderInboxWorker;
import dev.genericpayments.platform.payments.application.service.ProviderReferenceInboxWorker;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties;
import java.util.concurrent.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="payment.inbox.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class ProviderInboxScheduler {
    private final ObjectProvider<ProviderInboxWorker> worker;
    private final ObjectProvider<ProviderReferenceInboxWorker> references;
    private final boolean stripeEnabled;
    private final long delay;
    private ScheduledThreadPoolExecutor referenceLane;
    public ProviderInboxScheduler(ObjectProvider<ProviderInboxWorker> worker,ObjectProvider<ProviderReferenceInboxWorker> references,
            StripeSandboxProperties stripe,@Value("${payment.inbox.delay-ms:1000}")long delay) {
        if(delay<100||delay>60000)throw new IllegalArgumentException("Invalid inbox delay");
        this.worker=worker;this.references=references;this.stripeEnabled=stripe.enabled();this.delay=delay;
    }
    @Scheduled(fixedDelayString="${payment.inbox.delay-ms:1000}",initialDelayString="${payment.inbox.delay-ms:1000}")public void run(){worker.getObject().runOnce();}
    /** One lifecycle-owned periodic task; never registers a TaskScheduler bean or replaces the shared scheduler. */
    @PostConstruct public synchronized void startReferences() {
        if(!stripeEnabled||referenceLane!=null)return;
        referenceLane=new ScheduledThreadPoolExecutor(1,r -> {var thread=new Thread(r,"provider-reference-inbox");thread.setDaemon(true);return thread;});
        referenceLane.setRemoveOnCancelPolicy(true);
        referenceLane.scheduleWithFixedDelay(() -> {
            try {references.getObject().runOnce();}
            catch(RuntimeException ignored) { /* A transient acquisition/storage failure must not cancel later ticks. */ }
        },delay,delay,TimeUnit.MILLISECONDS);
    }
    @PreDestroy public synchronized void stopReferences() {
        if(referenceLane!=null)referenceLane.shutdownNow();
    }
}
