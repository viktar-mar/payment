package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.application.model.*;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Import(CustomerAnonymizationServiceIntegrationTest.RedactionConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@org.springframework.boot.test.context.SpringBootTest(properties="payment.telemetry.site-keys[0]=test-site")
class CustomerAnonymizationServiceIntegrationTest extends ProviderTestFixture {
    @Autowired AnonymizeCustomerUseCase anonymizer;
    @Autowired CustomerStore customers;
    @Autowired CustomerTransactionPort transactions;
    @Autowired IdentityProviderConfigurationStore providers;
    @Autowired CustomerIdentityStore identities;
    @Autowired CustomerSessionService sessions;
    @Autowired CustomerAuthenticationCompletionService completion;
    @Autowired EmailOtpChallengeStore challenges;
    @Autowired IdentityLinkProofStore proofs;
    @Autowired KeyedDigestPort digests;
    @Autowired EncryptedSecretPort encryption;
    @Autowired TestRedaction redaction;
    @Autowired dev.genericpayments.platform.payments.application.service.RefundCoordinator refunds;
    private Customer customer;
    private UUID paymentId;
    private UUID matchingProof;
    private UUID unrelatedProof;
    private EmailOtpChallengeId relatedOtp;
    private EmailOtpChallengeId identityOtp;
    private EmailOtpChallengeId unrelatedOtp;
    private static final String EMAIL="erase-private@example.test", SUBJECT="erase-private-subject", KEY="erase-private-idempotency";
    @BeforeEach void fixture() {
        redaction.reset();
        providers.save(IdentityProviderConfiguration.create(site,"email-provider",new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));
        var id=new CustomerId(UUID.randomUUID());
        customer=customers.save(Customer.create(id,site,CustomerStore.emailReference(id),ref(KeyedDigestPort.Purpose.EMAIL_LOOKUP,EMAIL),INITIAL_TIME),encryption.encrypt(EMAIL));
        addIdentity(CustomerAuthenticationMode.SITE_TOKEN,"safe-provider",SUBJECT);
        addIdentity(CustomerAuthenticationMode.EMAIL_OTP,"email-provider","identity-only@example.test");
        sessions.issue(site,id,"https://shop.test"); sessions.issue(site,id,"https://shop.test");
        relatedOtp=challenge(EMAIL,"email-provider"); identityOtp=challenge("identity-only@example.test","email-provider");
        unrelatedOtp=challenge("unrelated@example.test","email-provider");
        matchingProof=proof(SUBJECT); unrelatedProof=proof("unrelated-subject");
        var payment=save.save(payment()); paymentId=payment.id().value();
        jdbc.update("update payments set customer_id=? where id=?",id.value(),paymentId);
    }
    private void addIdentity(CustomerAuthenticationMode mode,String issuer,String subject) {
        String digest=ref(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject);
        identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,customer.id(),mode,issuer,digest,INITIAL_TIME),List.of(digest));
    }
    private EmailOtpChallengeId challenge(String email,String provider) {
        var id=new EmailOtpChallengeId(UUID.randomUUID());
        challenges.save(EmailOtpChallenge.create(id,site,"https://shop.test",AuthenticationIntent.AUTHENTICATE,provider,
            ref(KeyedDigestPort.Purpose.EMAIL_LOOKUP,email),EmailOtpChallengeStore.emailReference(id),ref(KeyedDigestPort.Purpose.OTP_CODE,"123456"),null,INITIAL_TIME,INITIAL_TIME.plusSeconds(300)),encryption.encrypt(email));
        return id;
    }
    private UUID proof(String subject) {
        var id=UUID.randomUUID(); var digest=ref(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject);
        var rotated="v2:"+digest.substring(digest.indexOf(':')+1);
        proofs.save(new IdentityLinkProof(id,site,"https://shop.test",CustomerAuthenticationMode.SITE_TOKEN,"safe-provider",rotated,List.of(rotated,digest),
            ref(KeyedDigestPort.Purpose.LINK_PROOF,UUID.randomUUID().toString()),INITIAL_TIME,INITIAL_TIME,INITIAL_TIME.plusSeconds(300),null,0));
        return id;
    }
    private String ref(KeyedDigestPort.Purpose purpose,String value){var digest=digests.digest(purpose,value);return digest.keyVersion()+":"+digest.value();}
    private AnonymizeCustomerUseCase.Command command(String key){return command(site,customer.id(),customer.id().value(),key);}
    private AnonymizeCustomerUseCase.Command command(SiteId tenant,CustomerId id,UUID confirmation,String key){
        return new AnonymizeCustomerUseCase.Command(tenant,id,confirmation,key,"ADMIN","00000000-0000-0000-0000-000000000001","erase-request","127.0.0.1");
    }
    @Test void removesAllAccessMaterialPreservesFinancialFactsAndAuditsWithoutSecrets(CapturedOutput output) {
        var financial=jdbc.queryForList("select * from payments");
        var result=anonymizer.anonymize(command(KEY));
        assertThat(result.customerId()).isEqualTo(customer.id()); assertThat(result.status()).isEqualTo(CustomerStatus.ANONYMIZED);
        var stored=customers.findById(site,customer.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(CustomerStatus.ANONYMIZED);
        assertThat(stored.encryptedEmailRef()).isEmpty(); assertThat(stored.emailDigest()).isEmpty();
        assertThat(stored.anonymizedAt()).contains(INITIAL_TIME); assertThat(stored.updatedAt()).isEqualTo(INITIAL_TIME);
        assertThat(customers.findEncryptedEmail(site,customer.id())).isEmpty();
        assertThat(jdbc.queryForMap("select email_ciphertext,email_nonce,email_key_version,email_digest,email_digest_key_version from customers where id=?",customer.id().value()).values()).containsOnlyNulls();
        assertThat(count("customer_identities")).isZero(); assertThat(count("customer_identity_subject_aliases")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions where revoked_at is null",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select revoked_reason from customer_sessions",String.class)).containsOnly("ANONYMIZED");
        for(var id:List.of(relatedOtp,identityOtp)) assertThat(jdbc.queryForObject("select invalidated_at is not null from email_otp_challenges where id=?",Boolean.class,id.value())).isTrue();
        assertThat(jdbc.queryForObject("select invalidated_at is null from email_otp_challenges where id=?",Boolean.class,unrelatedOtp.value())).isTrue();
        assertThat(jdbc.queryForObject("select consumed_at is not null from identity_link_proofs where id=?",Boolean.class,matchingProof)).isTrue();
        assertThat(jdbc.queryForObject("select consumed_at is null from identity_link_proofs where id=?",Boolean.class,unrelatedProof)).isTrue();
        assertThat(jdbc.queryForList("select * from payments")).isEqualTo(financial);
        assertThat(redaction.calls).isEqualTo(1); assertThat(redaction.site).isEqualTo(site); assertThat(redaction.customer).isEqualTo(customer.id());
        var audit=jdbc.queryForList("select * from audit_log").toString();
        assertThat(audit).contains("CUSTOMER_ANONYMIZED",site.value().toString(),customer.id().value().toString(),"erase-request","ANONYMIZED");
        assertThat(audit+jdbc.queryForList("select * from customer_idempotency_records")+output.getAll()).doesNotContain(EMAIL,SUBJECT,KEY,"123456");
        assertThatThrownBy(() -> customers.save(stored.attachEmail(CustomerStore.emailReference(stored.id()),"v1:abc",INITIAL_TIME))).isInstanceOf(RuntimeException.class);
    }
    @Test void completedReplayAndNewKeyRepeatNeverRedoEffectsAndChangedTargetConflicts() {
        var first=anonymizer.anonymize(command(KEY));
        assertThat(anonymizer.anonymize(command(KEY))).isEqualTo(first);
        assertThat(anonymizer.anonymize(command("new-key"))).isEqualTo(first);
        assertThat(redaction.calls).isEqualTo(1); assertThat(count("audit_log")).isOne(); assertThat(count("customer_idempotency_records")).isEqualTo(2);
        var unknown=new CustomerId(UUID.randomUUID());
        assertThatThrownBy(() -> anonymizer.anonymize(command(site,unknown,unknown.value(),KEY)))
            .isInstanceOfSatisfying(PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void concurrentSameAndDifferentKeysCompleteExactlyOnce(boolean differentKeys) throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);
            var one=executor.submit(()->{gate.await();return anonymizer.anonymize(command(KEY));});
            var two=executor.submit(()->{gate.await();return anonymizer.anonymize(command(differentKeys?"second-key":KEY));});
            gate.countDown();assertThat(one.get(10,TimeUnit.SECONDS)).isEqualTo(two.get(10,TimeUnit.SECONDS));
        }
        assertThat(redaction.calls).isOne();assertThat(count("audit_log")).isOne();
        assertThat(count("customer_idempotency_records")).isEqualTo(differentKeys?2:1);
    }
    @Test void confirmationUnknownAndCrossSiteTargetsCannotMutateOrClaimKeys() {
        assertThatThrownBy(() -> anonymizer.anonymize(command(site,customer.id(),UUID.randomUUID(),KEY))).isInstanceOf(RuntimeException.class);
        var unknown=new CustomerId(UUID.randomUUID());
        assertThatThrownBy(() -> anonymizer.anonymize(command(site,unknown,unknown.value(),KEY)))
            .isInstanceOfSatisfying(PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("CUSTOMER_NOT_AVAILABLE"));
        var other=new SiteId(UUID.randomUUID());
        jdbc.update("insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version) values (?,'erase-other','Other','ACTIVE',?,'AUTOMATIC',?,?,0)",
            other.value(),account.id().value(),java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,'https://other.test')",other.value());
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'SITE_TOKEN')",other.value());
        assertThatThrownBy(() -> anonymizer.anonymize(command(other,customer.id(),customer.id().value(),KEY)))
            .isInstanceOfSatisfying(PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("CUSTOMER_NOT_AVAILABLE"));
        assertThat(count("customer_idempotency_records")).isZero(); assertThat(redaction.calls).isZero();
        assertThat(customers.findById(site,customer.id()).orElseThrow().status()).isEqualTo(CustomerStatus.ACTIVE);
    }
    @Test void redactionFailureRollsBackCustomerCapabilitiesAuditAndIdempotencyClaim() {
        redaction.fail=true;
        assertThatThrownBy(() -> anonymizer.anonymize(command(KEY))).isInstanceOf(IllegalStateException.class).hasMessage("Injected redaction failure");
        assertThat(redaction.calls).isOne();
        assertRolledBack();
    }
    @Test void auditFailureRollsBackAllEarlierEffectsAndClaim() {
        jdbc.execute("create function task8_fail_audit() returns trigger language plpgsql as $$ begin raise exception 'injected audit failure'; end; $$");
        jdbc.execute("create trigger task8_fail_audit before insert on audit_log for each row execute function task8_fail_audit()");
        try { assertThatThrownBy(() -> anonymizer.anonymize(command(KEY))).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("injected audit failure"); assertThat(redaction.calls).isOne(); assertRolledBack(); }
        finally {jdbc.execute("drop trigger task8_fail_audit on audit_log");jdbc.execute("drop function task8_fail_audit()");}
    }
    private void assertRolledBack() {
        assertThat(customers.findById(site,customer.id()).orElseThrow().status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(customers.findEncryptedEmail(site,customer.id())).isPresent();
        assertThat(count("customer_identities")).isEqualTo(2); assertThat(count("customer_identity_subject_aliases")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions where revoked_at is null",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges where invalidated_at is not null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs where consumed_at is not null",Integer.class)).isZero();
        assertThat(count("customer_idempotency_records")).isZero(); assertThat(count("audit_log")).isZero();
        assertThat(jdbc.queryForObject("select customer_id from payments where id=?",UUID.class,paymentId)).isEqualTo(customer.id().value());
    }
    @Test void expiredHistoricalOtpDoesNotRequireDecryptingRetiredEmailMaterial() {
        jdbc.update("update email_otp_challenges set created_at=?,expires_at=?,email_key_version='retired-encryption-key' where id=?",
            java.sql.Timestamp.from(INITIAL_TIME.minusSeconds(300)),java.sql.Timestamp.from(INITIAL_TIME),unrelatedOtp.value());
        assertThat(anonymizer.anonymize(command(KEY)).status()).isEqualTo(CustomerStatus.ANONYMIZED);
        assertThat(jdbc.queryForObject("select invalidated_at is null from email_otp_challenges where id=?",Boolean.class,unrelatedOtp.value())).isTrue();
    }
    @Test void nonemptyPaymentEventRefundAndProviderFactsRemainByteForByteUnchanged() {
        var p=load.findById(site,new dev.genericpayments.platform.payments.domain.PaymentId(paymentId)).orElseThrow();
        fake.preparePayment(request(p));
        p=save.save(p.withProviderPaymentReference("fake_pi_"+p.id().value(),INITIAL_TIME).succeeded(INITIAL_TIME));
        refunds.create(new dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand(site,p.id(),
            UUID.fromString("30000000-0000-0000-0000-000000000001"),700,"EUR","REQUESTED_BY_CUSTOMER",null,"financial-refund","financial-request"));
        var tables=List.of("payments","payment_events","refunds","fake_provider_objects");
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:tables){var rows=jdbc.queryForList("select * from "+table+" order by 1");assertThat(rows).isNotEmpty();before.put(table,rows);}
        anonymizer.anonymize(command(KEY));
        for(String table:tables)assertThat(jdbc.queryForList("select * from "+table+" order by 1")).isEqualTo(before.get(table));
        assertThat(jdbc.queryForObject("select customer_id from payments where id=?",UUID.class,paymentId)).isEqualTo(customer.id().value());
    }
    @Test void anonymizationWinsAgainstQueuedSessionAndAuthenticationWithoutReconnectingHistory() throws Exception {
        redaction.entered=new CountDownLatch(1); redaction.release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var erase=executor.submit(() -> anonymizer.anonymize(command(KEY)));
            assertThat(redaction.entered.await(10,TimeUnit.SECONDS)).isTrue();
            var oldSession=executor.submit(() -> sessions.issue(site,customer.id(),"https://shop.test"));
            var auth=executor.submit(() -> completion.authenticate(site,CustomerAuthenticationMode.SITE_TOKEN,
                new VerifiedIdentityEvidence(SUBJECT,"safe-provider",Optional.empty(),Optional.empty(),INITIAL_TIME,INITIAL_TIME.plusSeconds(300)),INITIAL_TIME,"https://shop.test"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline && jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%sites%'",Integer.class)<2) Thread.sleep(10);
            assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%sites%'",Integer.class)).isGreaterThanOrEqualTo(2);
            redaction.release.countDown(); erase.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(() -> oldSession.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(PaymentApplicationException.class);
            var newCustomer=auth.get(10,TimeUnit.SECONDS).customerId(); assertThat(newCustomer).isNotEqualTo(customer.id());
            assertThat(jdbc.queryForObject("select count(*) from customer_sessions where customer_id=? and revoked_at is null",Integer.class,customer.id().value())).isZero();
            assertThat(identities.list(site,customer.id())).isEmpty(); assertThat(identities.list(site,newCustomer)).hasSize(1);
            assertThat(jdbc.queryForObject("select customer_id from payments where id=?",UUID.class,paymentId)).isEqualTo(customer.id().value());
        } finally {redaction.release.countDown();}
    }
    @Test void anonymizationAndCrossCustomerLinkUseCompatibleAliasIdentityLockOrder() throws Exception {
        var target=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var subject=ref(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,SUBJECT);
        var owner=identities.list(site,customer.id()).stream().filter(identity -> identity.issuerKey().equals("safe-provider")).findFirst().orElseThrow();
        var blockerPid=new CompletableFuture<Integer>();
        var erasePid=new CompletableFuture<Integer>();
        var linkPid=new CompletableFuture<Integer>();
        var release=new CountDownLatch(1);
        var executor=Executors.newVirtualThreadPerTaskExecutor();
        try {
            var blocker=executor.submit(() -> transactions.required(() -> {
                boundedLockWaits();
                jdbc.queryForObject("select id from customer_identities where site_id=? and id=? for update",UUID.class,site.value(),owner.id().value());
                blockerPid.complete(backendPid());
                try { assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                return true;
            }));
            int holdingPid=blockerPid.get(10,TimeUnit.SECONDS);
            var erase=executor.submit(() -> transactions.required(() -> {
                boundedLockWaits();erasePid.complete(backendPid());
                return anonymizer.anonymize(command(KEY));
            }));
            int deletingPid=erasePid.get(10,TimeUnit.SECONDS);
            awaitBlockedBy(deletingPid,holdingPid);
            // Exercise the shared linking critical section under the other Customer's lock.
            // Its HTTP/proof wrappers are deliberately outside this persistence-lock regression.
            var link=executor.submit(() -> transactions.required(() -> {
                boundedLockWaits();linkPid.complete(backendPid());
                customers.findByIdForUpdate(site,target.id()).orElseThrow();
                return new IdentityLinkingSupport(identities).link(site,target.id(),CustomerAuthenticationMode.SITE_TOKEN,
                    "safe-provider",List.of(subject),INITIAL_TIME,INITIAL_TIME);
            }));
            awaitBlockedBy(linkPid.get(10,TimeUnit.SECONDS),deletingPid);
            release.countDown();
            blocker.get(10,TimeUnit.SECONDS);
            assertThat(erase.get(15,TimeUnit.SECONDS).status()).isEqualTo(CustomerStatus.ANONYMIZED);
            assertThat(link.get(15,TimeUnit.SECONDS).outcome()).isEqualTo(IdentityLinkingSupport.Outcome.LINKED);
            assertThat(customers.findById(site,customer.id()).orElseThrow().status()).isEqualTo(CustomerStatus.ANONYMIZED);
            assertThat(identities.list(site,customer.id())).isEmpty();
            assertThat(identities.list(site,target.id())).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from customer_identity_subject_aliases where site_id=?",Integer.class,site.value())).isOne();
            assertThat(jdbc.queryForObject("select count(*) from customer_sessions where customer_id=? and revoked_at is null",Integer.class,customer.id().value())).isZero();
            assertThat(jdbc.queryForObject("select customer_id from payments where id=?",UUID.class,paymentId)).isEqualTo(customer.id().value());
        } finally {
            release.countDown();executor.shutdownNow();
            assertThat(executor.awaitTermination(20,TimeUnit.SECONDS)).isTrue();
        }
    }
    private int backendPid(){return jdbc.queryForObject("select pg_backend_pid()",Integer.class);}
    private void boundedLockWaits(){jdbc.execute("set local lock_timeout='10s'");jdbc.execute("set local statement_timeout='15s'");}
    private void awaitBlockedBy(int waiter,int blocker) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",Boolean.class,blocker,waiter)).isTrue());
    }
    @TestConfiguration(proxyBeanMethods=false) static class RedactionConfiguration {
        @Bean @Primary TestRedaction task8Redaction(){return new TestRedaction();}
    }
    static class TestRedaction implements CustomerDataRedactionPort {
        int calls; boolean fail; SiteId site; CustomerId customer; CountDownLatch entered,release;
        void reset(){calls=0;fail=false;site=null;customer=null;entered=null;release=null;}
        public void redactCustomerData(SiteId site,CustomerId customer,Instant now) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            this.site=site;this.customer=customer;calls++;
            if(entered!=null){entered.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Test timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
            if(fail)throw new IllegalStateException("Injected redaction failure");
        }
    }
}
