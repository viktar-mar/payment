package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import java.util.UUID;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.ProviderInboxPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionTemplate;

class ProviderReferenceReceiptIntegrationTest extends ProviderTestFixture {
    static final Instant NOW=Instant.parse("2026-10-14T12:00:00Z");
    @TestBean(name="providerReferenceClock",methodName="referenceClock") Clock clock;
    static final AtomicReference<Instant> time=new AtomicReference<>(NOW);
    static Clock referenceClock() {return new Clock() {
        public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(java.time.ZoneId zone){return this;}
        public Instant instant(){return time.get();}
    };}
    @Autowired ProviderInboxPort inbox;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @BeforeEach void resetReferenceClock() {time.set(NOW);}
    ProviderReferenceReceipt receipt(String id) {return new ProviderReferenceReceipt(PaymentProviderType.STRIPE,account.id(),id,"pi_test",
        ProviderReferenceReceipt.ObjectType.PAYMENT,"payment_intent.succeeded","2026-09-30.endive",NOW.minusSeconds(1),NOW);}
    TransactionTemplate tx() {return new TransactionTemplate(transactions);}
    @Test void referenceFamilyFitsTheExistingInbox() {
        assertThatCode(() -> jdbc.update("""
            insert into provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_type,
              occurred_at,received_at,payload_hash,event_family,reference_object_type,provider_event_type,provider_api_version)
            values (?,'STRIPE',?,'evt_test','pi_test','PROVIDER_REFERENCE',current_timestamp,current_timestamp,?,
              'PROVIDER_REFERENCE','PAYMENT','payment_intent.succeeded','2026-09-30.endive')
            """,UUID.randomUUID(),account.id().value(),"0".repeat(64))).doesNotThrowAnyException();
        assertThat(count("provider_inbox")).isEqualTo(1);
    }
    @Test void referenceInsertDeduplicatesAndLegacyClaimsCannotConsumeIt() {
        assertThat(inbox.insertReference(receipt("evt_once"))).isTrue();assertThat(inbox.insertReference(receipt("evt_once"))).isFalse();
        assertThat(inbox.claim("legacy",10,NOW,Duration.ofSeconds(30))).isEmpty();
        var claim=inbox.claimReference(Duration.ofSeconds(30)).orElseThrow();
        assertThat(inbox.reference(claim)).contains(receipt("evt_once"));
        assertThat(inbox.find(claim.id())).isEmpty();
        assertThatThrownBy(() -> tx().execute(s -> inbox.lock(claim,NOW))).hasMessage("CLAIM_LOST");
        var locked=tx().execute(s -> inbox.lockReference(claim));assertThat(locked).isEqualTo(receipt("evt_once"));
        tx().executeWithoutResult(s -> inbox.completeReference(claim));
        assertThat(inbox.reference(claim)).isEmpty();assertThat(inbox.failReference(claim,"PROCESSING_FAILED")).isFalse();
        assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("PROCESSED");
    }
    @Test void expiredClaimCannotReadCompleteOrFailAndReclaimFencesOldOwner() {
        inbox.insertReference(receipt("evt_fence"));var first=inbox.claimReference(Duration.ofSeconds(30)).orElseThrow();
        assertThat(inbox.claimReference(Duration.ofSeconds(30))).isEmpty();
        time.set(NOW.plusSeconds(30));assertThat(inbox.reference(first)).isEmpty();
        assertThat(inbox.failReference(first,"PROCESSING_FAILED")).isFalse();
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> inbox.completeReference(first))).hasMessage("CLAIM_LOST");
        var second=inbox.claimReference(Duration.ofSeconds(30)).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());assertThat(second.owner()).isNotEqualTo(first.owner());
        assertThat(inbox.reference(first)).isEmpty();assertThat(inbox.failReference(first,"PROCESSING_FAILED")).isFalse();
        assertThatThrownBy(() -> tx().execute(s -> inbox.lockReference(first))).hasMessage("CLAIM_LOST");
        assertThat(inbox.reference(second)).isPresent();
    }
    @Test void concurrentAcquisitionHasOneFreshOwner() throws Exception {
        inbox.insertReference(receipt("evt_race"));var barrier=new CyclicBarrier(3);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var calls=new java.util.ArrayList<Future<java.util.Optional<ProviderInboxPort.Claim>>>();
            for(int i=0;i<3;i++)calls.add(pool.submit(() -> {barrier.await(5,TimeUnit.SECONDS);return inbox.claimReference(Duration.ofSeconds(30));}));
            int acquired=0;for(var future:calls)if(future.get(10,TimeUnit.SECONDS).isPresent())acquired++;
            assertThat(acquired).isEqualTo(1);
        }
    }
    @Test void retriesBackOffAndTwelfthFailureIsDead() {
        inbox.insertReference(receipt("evt_retry"));
        long[] delays={5,10,20,40,80,160,300,300,300,300,300,300};
        for(int attempt=0;attempt<12;attempt++) {
            var claim=inbox.claimReference(Duration.ofSeconds(30)).orElseThrow();
            assertThat(inbox.failReference(claim,"UNRESOLVED_OBJECT")).isTrue();
            Instant due=jdbc.queryForObject("select next_attempt_at from provider_inbox",java.sql.Timestamp.class).toInstant();
            assertThat(due).isEqualTo(time.get().plusSeconds(delays[attempt]));
            assertThat(inbox.claimReference(Duration.ofSeconds(30))).isEmpty();time.set(due);
        }
        assertThat(inbox.claimReference(Duration.ofSeconds(30))).isEmpty();
        assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select attempt_count from provider_inbox",Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("select lease_owner from provider_inbox",String.class)).isNull();
    }
    @Test void repeatedCrashRecoveryStopsAtTwelveWithoutKillingLiveTwelfthClaim() {
        inbox.insertReference(receipt("evt_crash"));ProviderInboxPort.Claim last=null;
        for(int n=0;n<12;n++) {
            last=inbox.claimReference(Duration.ofSeconds(1)).orElseThrow();
            if(n==11) {assertThat(inbox.claimReference(Duration.ofSeconds(1))).isEmpty();assertThat(inbox.reference(last)).isPresent();}
            time.set(time.get().plusSeconds(1));
        }
        assertThat(inbox.claimReference(Duration.ofSeconds(1))).isEmpty();assertThat(inbox.reference(last)).isEmpty();
        assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select attempt_count from provider_inbox",Integer.class)).isEqualTo(12);
    }
    @Test void everyReferenceOperationChecksTimeAfterWaitingForTheRowLock() throws Exception {
        for(String operation:java.util.List.of("read","lock","complete","fail")) {
            jdbc.update("delete from provider_inbox");time.set(NOW);inbox.insertReference(receipt("evt_wait"));
            var claim=inbox.claimReference(Duration.ofSeconds(30)).orElseThrow();
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var starting=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var holder=pool.submit(() -> tx().executeWithoutResult(s -> {
                    jdbc.queryForObject("select id from provider_inbox where id=? for update",UUID.class,claim.id());locked.countDown();
                    try {if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("lock release timed out");}catch(InterruptedException e){throw new RuntimeException(e);}
                }));
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var waiting=pool.submit(() -> {starting.countDown();return switch(operation) {
                    case "read" -> inbox.reference(claim).isEmpty();
                    case "fail" -> !inbox.failReference(claim,"PROCESSING_FAILED");
                    default -> {try {tx().executeWithoutResult(s -> {if(operation.equals("lock"))inbox.lockReference(claim);else inbox.completeReference(claim);});yield false;}
                        catch(RuntimeException lost) {yield lost.getMessage().equals("CLAIM_LOST");}}
                };});
                assertThat(starting.await(5,TimeUnit.SECONDS)).isTrue();
                // Observe the actual database lock wait before advancing the dedicated clock.
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
                while(System.nanoTime()<until) {
                    blocked=jdbc.queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query like '%provider_inbox%' and pid<>pg_backend_pid())",Boolean.class);
                    if(blocked)break;Thread.sleep(10);
                }
                assertThat(blocked).isTrue();time.set(NOW.plusSeconds(30));release.countDown();holder.get(5,TimeUnit.SECONDS);
                assertThat(waiting.get(5,TimeUnit.SECONDS)).as(operation).isTrue();
            } finally {release.countDown();}
            assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("PROCESSING");
        }
    }
    @Test void businessClockCannotExpireReferenceLeaseAndInvalidLeaseDoesNotAcquire() {
        inbox.insertReference(receipt("evt_clock"));
        for(Duration lease:java.util.List.of(Duration.ZERO,Duration.ofSeconds(601)))assertThatThrownBy(() -> inbox.claimReference(lease)).isInstanceOf(RuntimeException.class);
        var claim=inbox.claimReference(Duration.ofSeconds(30)).orElseThrow();
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(NOW.plusSeconds(10000));
        assertThat(inbox.reference(claim)).isPresent();time.set(NOW.plusSeconds(30));assertThat(inbox.reference(claim)).isEmpty();
    }
    @Test void referenceReadReleasesItsDatabaseLockBeforeCallerDoesExternalWork() throws Exception {
        inbox.insertReference(receipt("evt_release"));var claim=inbox.claimReference(Duration.ofSeconds(30)).orElseThrow();
        assertThat(inbox.reference(claim)).isPresent();
        try(var pool=Executors.newSingleThreadExecutor()) {
            var acquired=pool.submit(() -> tx().execute(s -> jdbc.queryForObject("select id from provider_inbox where id=? for update nowait",UUID.class,claim.id())));
            assertThat(acquired.get(5,TimeUnit.SECONDS)).isEqualTo(claim.id());
        }
    }
    @Test void schemaRejectsUnsafeReferencePayloadWrongTypesAndPrematureDead() {
        inbox.insertReference(receipt("evt_schema"));
        for(String change:java.util.List.of("safe_payload='{\"raw\":\"forbidden\"}'::jsonb","reference_object_type='REFUND'",
                "provider_api_version='wrong'","provider_type='FAKE'","status='DEAD'","attempt_count=13"))
            assertThatThrownBy(() -> jdbc.update("update provider_inbox set "+change)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("provider_inbox")).isEqualTo(1);
    }
}

/** Default Fake boot still supplies the real clock without enabling Stripe or adding credentials. */
class ProviderReferenceDefaultClockIntegrationTest extends ProviderTestFixture {
    @Autowired Clock businessClock;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("providerReferenceClock") Clock referenceClock;
    @Autowired ProviderInboxPort inbox;
    @Autowired dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties stripe;
    @Test void defaultFakeConfigurationKeepsNamedRealUtcSeparateFromFrozenBusinessClock() {
        assertThat(stripe.enabled()).isFalse();assertThat(businessClock.instant()).isEqualTo(INITIAL_TIME);
        assertThat(referenceClock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(Duration.between(referenceClock.instant(),Instant.now()).abs()).isLessThan(Duration.ofSeconds(2));
        Instant now=referenceClock.instant();
        inbox.insertReference(new ProviderReferenceReceipt(PaymentProviderType.STRIPE,account.id(),"evt_default","pi_test",
            ProviderReferenceReceipt.ObjectType.PAYMENT,"payment_intent.processing","2026-09-30.endive",now,now));
        var claim=inbox.claimReference(Duration.ofSeconds(30)).orElseThrow();
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(now.plusSeconds(10000));
        assertThat(inbox.reference(claim)).isPresent();
    }
}
