package dev.genericpayments.platform.provider.adapter.in.configuration;

import dev.genericpayments.platform.payments.application.service.StripeRecoveryService;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        name = "payment.stripe.recovery.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class StripeRecoveryScheduler {
    private final ObjectProvider<StripeRecoveryService> service;
    private final boolean enabled;
    private final long delay;
    private ScheduledThreadPoolExecutor lane;

    public StripeRecoveryScheduler(
            ObjectProvider<StripeRecoveryService> service,
            StripeSandboxProperties stripe,
            @Value("${payment.stripe.recovery.delay-ms:30000}") long delay) {
        if (delay < 100 || delay > 60000)
            throw new IllegalArgumentException("Invalid recovery delay");
        this.service = service;
        this.enabled = stripe.enabled();
        this.delay = delay;
    }
    /** One fixed-delay task, max20 JIT items, no per-item submissions or shared scheduler replacement. */
    @PostConstruct
    public synchronized void start() {
        if (!enabled || lane != null) return;
        lane = new ScheduledThreadPoolExecutor(1, r -> {
            var thread = new Thread(r, "stripe-recovery");
            thread.setDaemon(true);
            return thread;
        });
        lane.setRemoveOnCancelPolicy(true);
        lane.scheduleWithFixedDelay(
                () -> {
                    try {
                        service.getObject().runOnce();
                    } catch (RuntimeException safe) {
                        /* Preserve later ticks after storage or acquisition failure. */
                    }
                },
                delay,
                delay,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void stop() {
        if (lane != null) lane.shutdownNow();
    }
}
