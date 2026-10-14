package dev.genericpayments.platform.customers.adapter.out.mail;

import dev.genericpayments.platform.customers.application.port.out.DistributedCustomerRateLimitPort;
import dev.genericpayments.platform.customers.application.port.out.EmailOtpPolicyPort;
import dev.genericpayments.platform.customers.application.service.CustomerMailDeliveryService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CustomerMailWorker {
    private final CustomerMailDeliveryService delivery; private final DistributedCustomerRateLimitPort limits; private final Clock clock;
    private final EmailOtpPolicyPort policy; private final String owner="customer-mail-"+UUID.randomUUID();
    public CustomerMailWorker(CustomerMailDeliveryService delivery,DistributedCustomerRateLimitPort limits,Clock clock,
            EmailOtpPolicyPort policy) {
        this.delivery=delivery;this.limits=limits;this.clock=clock;this.policy=policy;
    }
    @Scheduled(fixedDelayString="${payment.customer-email-otp.outbox.delay:PT1S}",initialDelayString="${payment.customer-email-otp.outbox.delay:PT1S}")
    public void run(){if(policy.mailWorkerEnabled())delivery.deliverReady(owner);}
    @Scheduled(cron="${payment.customer-email-otp.cleanup-cron:0 30 3 * * *}",zone="UTC")
    public void cleanup(){if(policy.mailWorkerEnabled()){delivery.cleanup();limits.cleanupExpired(clock.instant(),policy.rateCleanupBatch());}}
}
