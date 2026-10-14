package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.adapter.in.configuration.CustomerEmailOtpProperties;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CustomerMailDeliveryServiceIntegrationTest extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerMailOutboxPort outbox;
    @Autowired EmailOtpChallengeStore challenges;
    @Autowired EncryptedSecretPort encryption;
    @Autowired CustomerEmailOtpProperties properties;
    SiteId site; EmailOtpChallengeId challenge;

    @BeforeEach void prepare() {
        clearDatabase(); site=new SiteId(UUID.randomUUID());challenge=new EmailOtpChallengeId(UUID.randomUUID());
        UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'delivery-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'Delivery',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),"delivery-site",account);
        jdbc.update("""
            insert into identity_provider_configurations(id,site_id,provider_key,mode,status,sender_ref,template_key,created_at,updated_at)
            values (?,?, 'email-otp','EMAIL_OTP','ACTIVE','test-default','otp-default',now(),now())
            """,UUID.randomUUID(),site.value());
        var encryptedEmail=encryption.encrypt("person@example.test");
        jdbc.update("""
            insert into email_otp_challenges(id,site_id,origin,intent,provider_key,email_digest,email_digest_key_version,
              email_ciphertext,email_nonce,email_key_version,otp_digest,otp_digest_key_version,attempt_count,
              created_at,expires_at,version) values (?,?,?,'AUTHENTICATE','email-otp',?,?,?,?,?,?,?,0,?,?,0)
            """,challenge.value(),site.value(),"https://mail.example.test","abcd","v1",encryptedEmail.ciphertext(),
            encryptedEmail.nonce(),encryptedEmail.keyVersion(),"dcba","v1",Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME.plusSeconds(600)));
    }

    @Test void transientFailureRetriesExponentiallyAndSmtpRunsOutsideEveryDatabaseTransaction() {
        RecordingDelivery delivery=new RecordingDelivery(CustomerMailDeliveryPort.FailureCategory.TRANSIENT);
        CustomerMailDeliveryService service=service(delivery); UUID job=saveMessage();

        assertThat(service.deliverReady("worker-a")).isOne();
        assertThat(delivery.transactionActive).containsExactly(false);
        assertThat(jdbc.queryForObject("select status from customer_mail_outbox where id=?",String.class,job)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select next_attempt_at from customer_mail_outbox where id=?",Timestamp.class,job).toInstant())
            .isEqualTo(INITIAL_TIME.plusSeconds(30));
        assertThat(jdbc.queryForObject("select payload_ciphertext is not null from customer_mail_outbox where id=?",Boolean.class,job)).isTrue();

        TestClock.set(INITIAL_TIME.plusSeconds(29)); assertThat(service.deliverReady("worker-b")).isZero();
        delivery.failure=null; TestClock.set(INITIAL_TIME.plusSeconds(30)); assertThat(service.deliverReady("worker-b")).isOne();
        assertThat(delivery.transactionActive).containsExactly(false,false);
        assertThat(jdbc.queryForObject("select status='SENT' and attempt_count=2 and payload_ciphertext is null and payload_destroyed_at is not null from customer_mail_outbox where id=?",
            Boolean.class,job)).isTrue();
        assertThat(delivery.messages).allSatisfy(message -> {
            assertThat(message.recipient()).isEqualTo("person@example.test");
            assertThat(message.body()).contains("654321");
        });
    }

    @Test void permanentAndConfigurationFailuresBecomeDeadWithoutLeakingPayload() {
        for(var category:List.of(CustomerMailDeliveryPort.FailureCategory.PERMANENT,CustomerMailDeliveryPort.FailureCategory.CONFIGURATION)) {
            UUID job=saveMessage(); RecordingDelivery delivery=new RecordingDelivery(category);
            service(delivery).deliverReady("worker-"+category);
            assertThat(jdbc.queryForObject("select status='DEAD' and error_category=? and payload_ciphertext is null and payload_destroyed_at is not null from customer_mail_outbox where id=?",
                Boolean.class,category.name(),job)).isTrue();
        }
    }

    @Test void malformedEncryptedMessageBecomesDeadAndTerminalMetadataExpiresAfterRetention() {
        UUID job=UUID.randomUUID();
        outbox.save(CustomerMailJob.pending(job,site,challenge,INITIAL_TIME),encryption.encrypt("not-a-rendered-mail-envelope"));
        var service=service(new RecordingDelivery(null)); service.deliverReady("worker");
        assertThat(jdbc.queryForObject("select status='DEAD' and payload_ciphertext is null from customer_mail_outbox where id=?",Boolean.class,job)).isTrue();

        TestClock.set(INITIAL_TIME.plus(properties.mailTerminalRetention()).plusSeconds(1)); service.cleanup();
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox where id=?",Integer.class,job)).isZero();
    }

    @Test void eachSequentialDeliveryClaimsImmediatelyBeforeSmtpAndLeaseCannotBeReclaimed() {
        saveMessage(); saveMessage(); List<CustomerMailOutboxPort.Claim> competing=new ArrayList<>();
        RecordingDelivery delivery=new RecordingDelivery(null);
        delivery.onDeliver=() -> competing.addAll(outbox.claimReady("worker-b",
            INITIAL_TIME.plus(properties.mailLease()).minusMillis(1),1,properties.mailLease()));

        assertThat(service(delivery).deliverReady("worker-a")).isOne();
        assertThat(competing).hasSize(1);
        assertThat(outbox.claimReady("worker-c",INITIAL_TIME.plus(properties.mailLease()).minusMillis(1),1,properties.mailLease())).isEmpty();
    }

    @Test void retryScheduleGrowsExponentiallyAndCapsAtThirtyMinutes() {
        UUID job=saveMessage(); RecordingDelivery delivery=new RecordingDelivery(CustomerMailDeliveryPort.FailureCategory.TRANSIENT);
        CustomerMailDeliveryService service=service(delivery);
        long[] expectedDelays={30,60,120,240,480,960,1800}; Instant attemptAt=INITIAL_TIME;
        for(long delay:expectedDelays) {
            TestClock.set(attemptAt); assertThat(service.deliverReady("retry-worker")).isOne();
            Instant next=jdbc.queryForObject("select next_attempt_at from customer_mail_outbox where id=?",Timestamp.class,job).toInstant();
            assertThat(next).isEqualTo(attemptAt.plusSeconds(delay)); attemptAt=next;
        }
        TestClock.set(attemptAt); assertThat(service.deliverReady("retry-worker")).isOne();
        assertThat(jdbc.queryForObject("select status='DEAD' and attempt_count=8 and error_category='EXHAUSTED' from customer_mail_outbox where id=?",Boolean.class,job)).isTrue();
    }

    @Test void transientRetryIsAnchoredAtThePostFailureInstantWhenTheClockAdvancesDuringSend() {
        UUID job=saveMessage();
        RecordingDelivery delivery=new RecordingDelivery(CustomerMailDeliveryPort.FailureCategory.TRANSIENT);
        delivery.onDeliver=() -> TestClock.set(INITIAL_TIME.plusSeconds(20));

        assertThat(service(delivery).deliverReady("clock-advance-worker")).isOne();

        var row=jdbc.queryForMap("select updated_at,next_attempt_at from customer_mail_outbox where id=?",job);
        Instant failedAt=((Timestamp)row.get("updated_at")).toInstant();
        assertThat(failedAt).isEqualTo(INITIAL_TIME.plusSeconds(20));
        assertThat(((Timestamp)row.get("next_attempt_at")).toInstant()).isEqualTo(failedAt.plusSeconds(30));
    }

    @Test void slowSendRenewsItsExactLeaseAndCannotBeClaimedAfterTheOriginalDeadline() throws Exception {
        Duration lease=Duration.ofMillis(900); UUID job=saveMessage();
        BlockingDelivery delivery=new BlockingDelivery();
        ObservingOutbox observed=new ObservingOutbox(outbox,false);
        CustomerMailDeliveryService service=service(delivery,new LeasePolicy(properties,lease),observed);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> workerA=executor.submit(() -> service.deliverReady("worker-a"));
            assertThat(delivery.started.await(10,TimeUnit.SECONDS)).isTrue();
            TestClock.set(INITIAL_TIME.plusMillis(600));
            awaitCondition(() -> leaseExpiry(job).isAfter(INITIAL_TIME.plus(lease)));

            TestClock.set(INITIAL_TIME.plusSeconds(1));
            assertThat(outbox.claimReady("worker-b",INITIAL_TIME.plusSeconds(1),1,lease)).isEmpty();
            delivery.release.countDown();
            assertThat(workerA.get(10,TimeUnit.SECONDS)).isOne();

            assertThat(observed.renewals.get()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForObject("select status='SENT' and attempt_count=1 from customer_mail_outbox where id=?",
                Boolean.class,job)).isTrue();
            observed.watchForPostCompletionRenewal.set(true);
            assertThat(observed.postCompletionRenewal.await(700,TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            delivery.release.countDown(); executor.shutdownNow();
        }
    }

    @Test void failedRenewalStopsTheGuardAndStaleSenderCannotFinalizeAReclaimedGeneration() throws Exception {
        Duration lease=Duration.ofMillis(900); UUID job=saveMessage();
        BlockingDelivery delivery=new BlockingDelivery();
        ObservingOutbox observed=new ObservingOutbox(outbox,true);
        CustomerMailDeliveryService service=service(delivery,new LeasePolicy(properties,lease),observed);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> workerA=executor.submit(() -> service.deliverReady("worker-a"));
            assertThat(delivery.started.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(observed.renewalAttempted.await(10,TimeUnit.SECONDS)).isTrue();
            TestClock.set(INITIAL_TIME.plusSeconds(1));
            var recovered=outbox.claimReady("worker-b",INITIAL_TIME.plusSeconds(1),1,lease).getFirst();

            delivery.release.countDown();
            assertThat(workerA.get(10,TimeUnit.SECONDS)).isOne();
            assertThat(jdbc.queryForObject("select status='PROCESSING' and lease_owner='worker-b' and attempt_count=2 from customer_mail_outbox where id=?",
                Boolean.class,job)).isTrue();
            assertThat(outbox.markSent(recovered,INITIAL_TIME.plusSeconds(1))).isTrue();

            assertThat(observed.renewals).hasValue(1);
            observed.watchForPostCompletionRenewal.set(true);
            assertThat(observed.postCompletionRenewal.await(700,TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            delivery.release.countDown(); executor.shutdownNow();
        }
    }

    @Test void cleanupDeletesMailMetadataBeforeItsExpiredChallenge() {
        UUID job=saveMessage(); Instant terminal=INITIAL_TIME.minus(Duration.ofDays(8));
        jdbc.update("update email_otp_challenges set created_at=?,expires_at=? where id=?",
            Timestamp.from(terminal.minusSeconds(600)),Timestamp.from(terminal),challenge.value());
        jdbc.update("""
            update customer_mail_outbox set status='SENT',payload_ciphertext=null,payload_nonce=null,payload_key_version=null,
              created_at=?,next_attempt_at=?,completed_at=?,payload_destroyed_at=?,updated_at=? where id=?
            """,Timestamp.from(terminal.minusSeconds(600)),Timestamp.from(terminal.minusSeconds(600)),
            Timestamp.from(terminal),Timestamp.from(terminal),Timestamp.from(terminal),job);

        service(new RecordingDelivery(null)).cleanup();

        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox where id=?",Integer.class,job)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges where id=?",Integer.class,challenge.value())).isZero();
    }

    private UUID saveMessage() {
        UUID id=UUID.randomUUID();
        String payload=new CustomerMailPayloadCodec().encode(new CustomerMailDeliveryPort.Message("test-default","person@example.test",
            "Subject-secret","Body-secret 654321"));
        outbox.save(CustomerMailJob.pending(id,site,challenge,INITIAL_TIME),encryption.encrypt(payload)); return id;
    }
    private CustomerMailDeliveryService service(CustomerMailDeliveryPort delivery) {
        return new CustomerMailDeliveryService(outbox,delivery,challenges,encryption,properties,new TestClock());
    }
    private CustomerMailDeliveryService service(CustomerMailDeliveryPort delivery,EmailOtpPolicyPort policy,CustomerMailOutboxPort selectedOutbox) {
        return new CustomerMailDeliveryService(selectedOutbox,delivery,challenges,encryption,policy,new TestClock());
    }
    private Instant leaseExpiry(UUID job) {
        Timestamp value=jdbc.queryForObject("select lease_expires_at from customer_mail_outbox where id=?",Timestamp.class,job);
        return value==null?Instant.MIN:value.toInstant();
    }
    private static void awaitCondition(BooleanSupplier condition) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>=deadline) fail("Condition was not met before timeout");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private static final class RecordingDelivery implements CustomerMailDeliveryPort {
        private final List<Boolean> transactionActive=new ArrayList<>();
        private final List<Message> messages=new ArrayList<>();
        private FailureCategory failure;
        private Runnable onDeliver=() -> {};
        private RecordingDelivery(FailureCategory failure){this.failure=failure;}
        @Override public void deliver(Message message) {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());messages.add(message);onDeliver.run();
            if(failure!=null)throw new DeliveryException(failure);
        }
    }

    private static final class BlockingDelivery implements CustomerMailDeliveryPort {
        private final CountDownLatch started=new CountDownLatch(1);
        private final CountDownLatch release=new CountDownLatch(1);
        @Override public void deliver(Message message) {
            started.countDown();
            try {
                if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Blocking delivery timed out");
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Blocking delivery interrupted",interrupted);
            }
        }
    }

    private static final class ObservingOutbox implements CustomerMailOutboxPort {
        private final CustomerMailOutboxPort delegate;
        private final boolean rejectRenewals;
        private final AtomicInteger renewals=new AtomicInteger();
        private final CountDownLatch renewalAttempted=new CountDownLatch(1);
        private final AtomicBoolean watchForPostCompletionRenewal=new AtomicBoolean();
        private final CountDownLatch postCompletionRenewal=new CountDownLatch(1);
        private ObservingOutbox(CustomerMailOutboxPort delegate,boolean rejectRenewals) {
            this.delegate=delegate; this.rejectRenewals=rejectRenewals;
        }
        @Override public CustomerMailJob save(CustomerMailJob job,EncryptedSecretPort.EncryptedValue payload) {
            return delegate.save(job,payload);
        }
        @Override public List<Claim> claimReady(String owner,Instant now,int batchSize,Duration lease) {
            return delegate.claimReady(owner,now,batchSize,lease);
        }
        @Override public boolean renewLease(Claim claim,Instant renewedAt,Instant leaseExpiresAt) {
            renewals.incrementAndGet(); renewalAttempted.countDown();
            if(watchForPostCompletionRenewal.get()) postCompletionRenewal.countDown();
            return !rejectRenewals&&delegate.renewLease(claim,renewedAt,leaseExpiresAt);
        }
        @Override public boolean markSent(Claim claim,Instant now){return delegate.markSent(claim,now);}
        @Override public boolean markFailure(Claim claim,CustomerMailJob.ErrorCategory category,Instant now,Instant nextAttemptAt) {
            return delegate.markFailure(claim,category,now,nextAttemptAt);
        }
        @Override public int destroyTerminalPayloads(Instant completedBefore,Instant now,int maximumRows) {
            return delegate.destroyTerminalPayloads(completedBefore,now,maximumRows);
        }
        @Override public int deleteTerminalMetadata(Instant completedBefore,int maximumRows) {
            return delegate.deleteTerminalMetadata(completedBefore,maximumRows);
        }
    }

    private static final class LeasePolicy implements EmailOtpPolicyPort {
        private final EmailOtpPolicyPort delegate; private final Duration lease;
        private LeasePolicy(EmailOtpPolicyPort delegate,Duration lease){this.delegate=delegate;this.lease=lease;}
        @Override public Duration ttl(){return delegate.ttl();}
        @Override public int maxAttempts(){return delegate.maxAttempts();}
        @Override public Duration resendCooldown(){return delegate.resendCooldown();}
        @Override public int siteRequestLimit(){return delegate.siteRequestLimit();}
        @Override public int emailRequestLimit(){return delegate.emailRequestLimit();}
        @Override public int sourceAddressRequestLimit(){return delegate.sourceAddressRequestLimit();}
        @Override public Duration rateWindow(){return delegate.rateWindow();}
        @Override public int mailBatchSize(){return 1;}
        @Override public Duration mailLease(){return lease;}
        @Override public int mailMaxAttempts(){return delegate.mailMaxAttempts();}
        @Override public Duration mailMaximumBackoff(){return delegate.mailMaximumBackoff();}
        @Override public Duration mailTerminalRetention(){return delegate.mailTerminalRetention();}
        @Override public int mailCleanupBatch(){return delegate.mailCleanupBatch();}
        @Override public Duration challengeRetention(){return delegate.challengeRetention();}
        @Override public int challengeCleanupBatch(){return delegate.challengeCleanupBatch();}
        @Override public boolean mailWorkerEnabled(){return delegate.mailWorkerEnabled();}
        @Override public Duration mailWorkerDelay(){return delegate.mailWorkerDelay();}
        @Override public int rateCleanupBatch(){return delegate.rateCleanupBatch();}
    }
}
