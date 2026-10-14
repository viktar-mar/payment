package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.CustomerMailJob;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CustomerMailDeliveryService {
    private final CustomerMailOutboxPort outbox; private final CustomerMailDeliveryPort delivery;
    private final EmailOtpChallengeStore challenges; private final EncryptedSecretPort encryption;
    private final EmailOtpPolicyPort properties; private final Clock clock;
    private final CustomerMailPayloadCodec payloads=new CustomerMailPayloadCodec();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CustomerTelemetryPort telemetry=CustomerTelemetryPort.NOOP;
    public CustomerMailDeliveryService(CustomerMailOutboxPort outbox,CustomerMailDeliveryPort delivery,EmailOtpChallengeStore challenges,
            EncryptedSecretPort encryption,EmailOtpPolicyPort properties,Clock clock) {
        this.outbox=outbox;this.delivery=delivery;this.challenges=challenges;this.encryption=encryption;this.properties=properties;this.clock=clock;
    }
    public int deliverReady(String owner) {
        int delivered=0;
        while(delivered<properties.mailBatchSize()) {
            var claims=outbox.claimReady(owner,clock.instant(),1,properties.mailLease());
            if(claims.isEmpty()) break;
            deliver(claims.getFirst()); delivered++;
        }
        return delivered;
    }
    private void deliver(CustomerMailOutboxPort.Claim claim) {
        CustomerMailJob job=claim.job(); CustomerMailJob.ErrorCategory failureCategory=null;
        LeaseGuard guard=new LeaseGuard(outbox,claim,properties.mailLease(),clock);
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("mail delivery transaction leak");
            String encoded=encryption.decrypt(claim.encryptedPayload());
            delivery.deliver(payloads.decode(encoded));
        } catch(CustomerMailDeliveryPort.DeliveryException failure) {
            failureCategory=switch(failure.category()) {
                case TRANSIENT -> CustomerMailJob.ErrorCategory.TRANSIENT;
                case PERMANENT -> CustomerMailJob.ErrorCategory.PERMANENT;
                case CONFIGURATION -> CustomerMailJob.ErrorCategory.CONFIGURATION;
            };
        } catch(RuntimeException failure) {
            failureCategory=CustomerMailJob.ErrorCategory.PERMANENT;
        } finally {
            guard.close();
        }
        if(!guard.ownsClaim()||Thread.currentThread().isInterrupted()) {telemetry.mail(CustomerTelemetryPort.MailOutcome.LEASE_LOST);return;}
        Instant finalizedAt=clock.instant();
        boolean finalized=failureCategory==null?outbox.markSent(claim,finalizedAt):outbox.markFailure(claim,failureCategory,finalizedAt,nextAttempt(job,finalizedAt));
        telemetry.mail(!finalized?CustomerTelemetryPort.MailOutcome.LEASE_LOST:failureCategory==null?CustomerTelemetryPort.MailOutcome.DELIVERED:
            switch(failureCategory) {
                case TRANSIENT -> CustomerTelemetryPort.MailOutcome.TRANSIENT;
                case CONFIGURATION -> CustomerTelemetryPort.MailOutcome.CONFIGURATION;
                default -> CustomerTelemetryPort.MailOutcome.PERMANENT;
            });
    }
    private Instant nextAttempt(CustomerMailJob job,Instant now) {
        long exponent=Math.min(20,Math.max(0,job.attemptCount()-1));
        Duration delay=Duration.ofSeconds(30L<<exponent);
        if(delay.compareTo(properties.mailMaximumBackoff())>0) delay=properties.mailMaximumBackoff();
        return now.plus(delay);
    }
    public void cleanup() {
        Instant now=clock.instant(),cutoff=now.minus(properties.mailTerminalRetention());
        outbox.destroyTerminalPayloads(cutoff,now,properties.mailCleanupBatch());
        outbox.deleteTerminalMetadata(cutoff,properties.mailCleanupBatch());
        challenges.deleteExpiredWithoutOutboxReferences(now.minus(properties.challengeRetention()),properties.challengeCleanupBatch());
    }

    private static final class LeaseGuard implements AutoCloseable {
        private final CountDownLatch stop=new CountDownLatch(1);
        private final AtomicBoolean ownsClaim=new AtomicBoolean(true);
        private final AtomicBoolean closed=new AtomicBoolean();
        private final Thread heartbeat;

        private LeaseGuard(CustomerMailOutboxPort outbox,CustomerMailOutboxPort.Claim claim,Duration lease,Clock clock) {
            long delayNanos=Math.max(TimeUnit.MILLISECONDS.toNanos(1),lease.toNanos()/3);
            heartbeat=Thread.ofVirtual().name("customer-mail-lease-"+claim.id()).start(() -> {
                try {
                    while(!stop.await(delayNanos,TimeUnit.NANOSECONDS)) {
                        Instant renewedAt=clock.instant();
                        if(!outbox.renewLease(claim,renewedAt,renewedAt.plus(lease))) {
                            ownsClaim.set(false); return;
                        }
                    }
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); ownsClaim.set(false);
                } catch(RuntimeException renewalFailure) {
                    ownsClaim.set(false);
                }
            });
        }
        private boolean ownsClaim(){return ownsClaim.get();}
        @Override public void close() {
            if(!closed.compareAndSet(false,true)) return;
            stop.countDown();
            boolean interrupted=Thread.interrupted();
            while(heartbeat.isAlive()) {
                try {heartbeat.join();}
                catch(InterruptedException waitInterrupted){interrupted=true;}
            }
            if(interrupted) Thread.currentThread().interrupt();
        }
    }
}
