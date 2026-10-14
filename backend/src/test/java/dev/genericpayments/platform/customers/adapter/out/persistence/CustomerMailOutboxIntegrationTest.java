package dev.genericpayments.platform.customers.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.time.*;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CustomerMailOutboxIntegrationTest extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerMailOutboxPort outbox;
    @Autowired EmailOtpChallengeStore challenges;
    @Autowired EncryptedSecretPort encryption;
    @Autowired EmailOtpPolicyPort policy;
    @Autowired dev.genericpayments.platform.customers.adapter.out.mail.CustomerMailWorker worker;
    SiteId site;
    EmailOtpChallengeId challenge;

    @Test void configuredDisabledWorkerCannotConsumeATestOwnedRow() {
        assertThat(policy.mailWorkerEnabled()).isFalse();
        var saved=outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,challenge,INITIAL_TIME),
            encryption.encrypt("invalid-payload-must-not-be-delivered"));

        worker.run();

        assertThat(jdbc.queryForObject("select status='PENDING' and attempt_count=0 and version=0 from customer_mail_outbox where id=?",
            Boolean.class,saved.id())).isTrue();
        var claims=outbox.claimReady("test-owned",INITIAL_TIME,1,Duration.ofSeconds(60));
        assertThat(claims).hasSize(1);
        assertThat(claims.getFirst().id()).isEqualTo(saved.id());
    }

    @BeforeEach void prepare() {
        clearDatabase();
        site=new SiteId(UUID.randomUUID()); challenge=new EmailOtpChallengeId(UUID.randomUUID());
        UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'mail-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'Mail',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),"mail-site",account);
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

    @Test void concurrentWorkersClaimDisjointJobsAndSuccessDestroysPayload() throws Exception {
        for(int i=0;i<2;i++) outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,challenge,INITIAL_TIME),
            encryption.encrypt("payload-"+i));

        ExecutorService executor=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        List<CustomerMailOutboxPort.Claim> first,second;
        try {
            Future<List<CustomerMailOutboxPort.Claim>> a=executor.submit(() -> {start.await();return outbox.claimReady("worker-a",INITIAL_TIME,1,Duration.ofSeconds(60));});
            Future<List<CustomerMailOutboxPort.Claim>> b=executor.submit(() -> {start.await();return outbox.claimReady("worker-b",INITIAL_TIME,1,Duration.ofSeconds(60));});
            start.countDown();first=a.get(10,TimeUnit.SECONDS);second=b.get(10,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}
        assertThat(first).hasSize(1); assertThat(second).hasSize(1);
        assertThat(second.getFirst().job().id()).isNotEqualTo(first.getFirst().job().id());

        assertThat(outbox.markSent(first.getFirst(),INITIAL_TIME.plusSeconds(1))).isTrue();
        assertThat(jdbc.queryForObject("select payload_ciphertext is null and status='SENT' from customer_mail_outbox where id=?",
            Boolean.class,first.getFirst().job().id())).isTrue();
    }

    @Test void expiredLeaseCanBeRecoveredAndEighthFailureIsTerminal() {
        var saved=outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,challenge,INITIAL_TIME),encryption.encrypt("payload"));
        CustomerMailOutboxPort.Claim claimed=null;
        for(int attempt=0;attempt<8;attempt++) {
            claimed=outbox.claimReady("worker-"+attempt,INITIAL_TIME.plusSeconds(attempt*61L),1,Duration.ofSeconds(60)).getFirst();
            outbox.markFailure(claimed,CustomerMailJob.ErrorCategory.TRANSIENT,INITIAL_TIME.plusSeconds(attempt*61L+1),
                INITIAL_TIME.plusSeconds((attempt+1)*61L));
        }
        assertThat(jdbc.queryForObject("select status from customer_mail_outbox where id=?",String.class,saved.id())).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select attempt_count from customer_mail_outbox where id=?",Integer.class,saved.id())).isEqualTo(8);
        assertThat(jdbc.queryForObject("select error_category from customer_mail_outbox where id=?",String.class,saved.id())).isEqualTo("EXHAUSTED");
        assertThat(jdbc.queryForObject("select payload_ciphertext is null and payload_destroyed_at is not null from customer_mail_outbox where id=?",
            Boolean.class,saved.id())).isTrue();
    }

    @Test void staleLeaseOwnerCannotFinalizeAfterRecovery() {
        var saved=outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,challenge,INITIAL_TIME),encryption.encrypt("payload"));
        var abandoned=outbox.claimReady("abandoned",INITIAL_TIME,1,Duration.ofSeconds(60)).getFirst();
        var recovered=outbox.claimReady("recovered",INITIAL_TIME.plusSeconds(61),1,Duration.ofSeconds(60)).getFirst();

        assertThat(outbox.markSent(abandoned,INITIAL_TIME.plusSeconds(62))).isFalse();
        assertThat(outbox.markSent(recovered,INITIAL_TIME.plusSeconds(62))).isTrue();
        assertThat(jdbc.queryForObject("select status from customer_mail_outbox where id=?",String.class,saved.id())).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select attempt_count from customer_mail_outbox where id=?",Integer.class,saved.id())).isEqualTo(2);
    }

    @Test void renewalExtendsOnlyTheExactLiveClaimAndNeverResurrectsAReclaimedGeneration() {
        var saved=outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,challenge,INITIAL_TIME),encryption.encrypt("payload"));
        var original=outbox.claimReady("worker-a",INITIAL_TIME,1,Duration.ofSeconds(60)).getFirst();

        assertThat(outbox.renewLease(original,INITIAL_TIME.plusSeconds(30),INITIAL_TIME.plusSeconds(90))).isTrue();
        assertThat(outbox.claimReady("worker-b",INITIAL_TIME.plusSeconds(61),1,Duration.ofSeconds(60))).isEmpty();

        var recovered=outbox.claimReady("worker-b",INITIAL_TIME.plusSeconds(91),1,Duration.ofSeconds(60)).getFirst();
        assertThat(outbox.renewLease(original,INITIAL_TIME.plusSeconds(92),INITIAL_TIME.plusSeconds(152))).isFalse();
        assertThat(outbox.markSent(original,INITIAL_TIME.plusSeconds(92))).isFalse();
        assertThat(outbox.markSent(recovered,INITIAL_TIME.plusSeconds(92))).isTrue();
        assertThat(jdbc.queryForObject("select status from customer_mail_outbox where id=?",String.class,saved.id())).isEqualTo("SENT");
    }

    @Test void expiredEighthLeaseBecomesExhaustedWithoutAForbiddenNinthClaim() {
        var saved=outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,challenge,INITIAL_TIME),encryption.encrypt("payload"));
        jdbc.update("""
            update customer_mail_outbox set status='PROCESSING',attempt_count=8,lease_owner='abandoned-eighth',
              lease_expires_at=?,updated_at=?,version=8 where id=?
            """,Timestamp.from(INITIAL_TIME.plusSeconds(60)),Timestamp.from(INITIAL_TIME),saved.id());

        assertThat(outbox.claimReady("recovery-worker",INITIAL_TIME.plusSeconds(61),1,Duration.ofSeconds(60))).isEmpty();
        assertThat(jdbc.queryForObject("""
            select status='DEAD' and attempt_count=8 and error_category='EXHAUSTED'
              and payload_ciphertext is null and payload_destroyed_at is not null
            from customer_mail_outbox where id=?
            """,Boolean.class,saved.id())).isTrue();
    }

    @Test void exhaustedTerminalizationIsBoundedByRequestedClaimLimit() {
        for(int index=0;index<8;index++) {
            var saved=outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,challenge,INITIAL_TIME),encryption.encrypt("payload"));
            jdbc.update("update customer_mail_outbox set attempt_count=8 where id=?",saved.id());
        }

        assertThat(outbox.claimReady("bounded-worker",INITIAL_TIME,3,Duration.ofSeconds(60))).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox where status='DEAD'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox where status='PENDING'",Integer.class)).isEqualTo(5);
    }

    @Test void concurrentExhaustedTerminalizationMakesDisjointBoundedProgress() throws Exception {
        for(int index=0;index<6;index++) {
            var saved=outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,challenge,INITIAL_TIME),encryption.encrypt("payload"));
            jdbc.update("update customer_mail_outbox set attempt_count=8 where id=?",saved.id());
        }
        ExecutorService executor=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Future<?> first=executor.submit(() -> {start.await();outbox.claimReady("terminal-a",INITIAL_TIME,2,Duration.ofSeconds(60));return null;});
            Future<?> second=executor.submit(() -> {start.await();outbox.claimReady("terminal-b",INITIAL_TIME,2,Duration.ofSeconds(60));return null;});
            start.countDown(); first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}

        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox where status='DEAD'",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox where status='PENDING'",Integer.class)).isEqualTo(2);
    }

    @Test void permanentFailureIsDeadImmediatelyAndStorageHasNoPlaintextMailColumnsOrValues() {
        String secret="recipient-secret@example.test OTP-654321 Subject-secret Body-secret";
        var saved=outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,challenge,INITIAL_TIME),encryption.encrypt(secret));
        String stored=jdbc.queryForObject("select encode(payload_ciphertext,'hex') from customer_mail_outbox where id=?",String.class,saved.id());
        assertThat(stored).doesNotContain("recipient-secret","654321","Subject-secret","Body-secret");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='customer_mail_outbox'",String.class))
            .doesNotContain("recipient","email","otp","subject","body","payload_plaintext");

        var claim=outbox.claimReady("worker",INITIAL_TIME,1,Duration.ofSeconds(60)).getFirst();
        assertThat(outbox.markFailure(claim,CustomerMailJob.ErrorCategory.PERMANENT,INITIAL_TIME.plusSeconds(1),INITIAL_TIME.plusSeconds(31))).isTrue();
        assertThat(jdbc.queryForObject("select status='DEAD' and payload_ciphertext is null and payload_destroyed_at is not null from customer_mail_outbox where id=?",
            Boolean.class,saved.id())).isTrue();
    }

    @Test void expiredChallengeCleanupHonorsCutoffBatchAndOutboxReferences() {
        List<UUID> old=new ArrayList<>();
        for(int index=0;index<4;index++) old.add(insertChallenge(INITIAL_TIME.minus(Duration.ofDays(3)).plusSeconds(index),
            INITIAL_TIME.minus(Duration.ofDays(2)).plusSeconds(index)));
        UUID recent=insertChallenge(INITIAL_TIME.minus(Duration.ofHours(30)),INITIAL_TIME.minus(Duration.ofHours(23)));
        UUID future=insertChallenge(INITIAL_TIME,INITIAL_TIME.plusSeconds(600));
        outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,new EmailOtpChallengeId(old.getFirst()),INITIAL_TIME),encryption.encrypt("payload"));

        assertThat(challenges.deleteExpiredWithoutOutboxReferences(INITIAL_TIME.minus(Duration.ofHours(24)),2)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges where id in (?,?,?,?)",Integer.class,
            old.get(0),old.get(1),old.get(2),old.get(3))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges where id in (?,?)",Integer.class,recent,future)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges where id=?",Integer.class,old.getFirst())).isOne();
    }

    private UUID insertChallenge(Instant created,Instant expires) {
        UUID id=UUID.randomUUID(); var email=encryption.encrypt("cleanup@example.test");
        jdbc.update("""
            insert into email_otp_challenges(id,site_id,origin,intent,provider_key,email_digest,email_digest_key_version,
              email_ciphertext,email_nonce,email_key_version,otp_digest,otp_digest_key_version,created_at,expires_at)
            values (?,?,'https://mail.example.test','AUTHENTICATE','email-otp','abcd','v1',?,?,?, 'dcba','v1',?,?)
            """,id,site.value(),email.ciphertext(),email.nonce(),email.keyVersion(),Timestamp.from(created),Timestamp.from(expires));
        return id;
    }
}
