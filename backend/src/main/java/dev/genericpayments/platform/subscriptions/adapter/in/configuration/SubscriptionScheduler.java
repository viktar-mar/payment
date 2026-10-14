package dev.genericpayments.platform.subscriptions.adapter.in.configuration;
import dev.genericpayments.platform.subscriptions.application.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="payment.subscriptions.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class SubscriptionScheduler {
    private final SubscriptionSweepWorker sweep;private final SubscriptionDeadlineWorker deadlines;
    public SubscriptionScheduler(SubscriptionSweepWorker sweep,SubscriptionDeadlineWorker deadlines){this.sweep=sweep;this.deadlines=deadlines;}
    @Scheduled(fixedDelayString="${payment.subscriptions.delay-ms:30000}",initialDelayString="${payment.subscriptions.delay-ms:30000}")
    public void run(){sweep.runOnce();deadlines.runOnce();}
}
