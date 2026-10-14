package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Catches duplicate overwrites, regressive facts and split commits in the real PostgreSQL kernel. */
@SpringBootTest(properties="payment.telemetry.site-keys[0]=test-site")
public class ProviderInboxServiceIntegrationTest extends ProviderTestFixture {
    @Autowired protected ProviderInboxService inbox;
    @Autowired protected ProviderInboxWorker worker;
    @Autowired protected ProviderInboxPort inboxPort;
    @Autowired protected SavePaymentAttemptPort attemptSave;
    protected Payment persisted() { return save.save(payment().withProviderPaymentReference("object-1", INITIAL_TIME)); }
    protected ProviderEventEnvelope envelope(String id,String type,String ref,Map<String,?> payload) {
        return new ProviderEventEnvelope(account.provider(),account.id(),id,ref,PaymentEventType.valueOf(type),INITIAL_TIME.minusSeconds(60),INITIAL_TIME,payload);
    }
    protected void process(String id,String type) { inbox.receive(envelope(id,type,"object-1",Map.of())); worker.runOnce(); }
    protected void callback(boolean enabled) {
        UUID secret=UUID.fromString("30000000-0000-0000-0000-000000000001");
        jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at) values (?,?,1,?,?,'test-v1',?)",secret,site.value(),new byte[32],new byte[12],java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into site_callback_configs(site_id,endpoint_url,active_secret_version_id,enabled,created_at,updated_at) values (?,'https://callback.test/exact',?,?,?,?)",site.value(),secret,enabled,java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
    }
    @Test void duplicatePreservesFirstImmutablePayloadAndFacts() {
        persisted(); inbox.receive(envelope("evt","PAYMENT_PROCESSING","object-1",Map.of("state","PROCESSING")));
        var first=jdbc.queryForMap("select * from provider_inbox");
        inbox.receive(envelope("evt","PAYMENT_SUCCEEDED","object-1",Map.of("state","SUCCEEDED")));
        assertThat(jdbc.queryForMap("select * from provider_inbox")).isEqualTo(first);
    }
    @Test void invalidIdentitiesTimesShapesAndPayloadSizeFailBeforeInsertion() {
        for(String id:new String[]{""," ","x".repeat(256),"\ud800"})assertThatThrownBy(() -> inbox.receive(envelope(id,"PAYMENT_SUCCEEDED","object-1",Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderEventEnvelope(account.provider(),account.id(),"id","object",PaymentEventType.PAYMENT_SUCCEEDED,INITIAL_TIME.plusSeconds(1),INITIAL_TIME,Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderEventEnvelope(account.provider(),account.id(),"id","object",PaymentEventType.PAYMENT_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of("nested",Map.of("state","SUCCEEDED")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.receive(envelope("id","PAYMENT_SUCCEEDED","object",Map.of("state","x".repeat(501))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("provider_inbox")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"PAYMENT_METHOD_REQUIRED","CUSTOMER_ACTION_REQUIRED","PAYMENT_PROCESSING"})
    void unconfirmedFactAdvancesProjectionAndUnknownAttempt(String type) {
        var p=persisted();var a=attemptSave.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"unknown","CARD",INITIAL_TIME).unknown("UNKNOWN",INITIAL_TIME),INITIAL_TIME);
        process("evt",type);var status=switch(type){case "PAYMENT_METHOD_REQUIRED" -> PaymentStatus.REQUIRES_PAYMENT_METHOD;case "CUSTOMER_ACTION_REQUIRED" -> PaymentStatus.REQUIRES_ACTION;default -> PaymentStatus.PROCESSING;};
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(status);assertThat(attempts.findById(site,a.value().id()).orElseThrow().value().status().name()).isEqualTo(status.name());
        assertThat(inboxPort.claim("other",1,INITIAL_TIME,java.time.Duration.ofSeconds(30))).isEmpty();
    }
    @Test void providerMismatchAndUnconfiguredTelemetryCannotInsert() {
        persisted();assertThatThrownBy(() -> inbox.receive(new ProviderEventEnvelope(dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE,account.id(),"id","object-1",PaymentEventType.PAYMENT_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of()))).isInstanceOf(RuntimeException.class);
        var guarded=new ProviderInboxService(inboxPort,transitionPort,digest,new dev.genericpayments.platform.shared.adapter.out.observability.MicrometerStage2TelemetryAdapter(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),Set.of()));
        assertThatThrownBy(() -> guarded.receive(envelope("id","PAYMENT_SUCCEEDED","object-1",Map.of()))).isInstanceOf(IllegalArgumentException.class);assertThat(count("provider_inbox")).isZero();
    }
    @Autowired PaymentTransitionPort transitionPort;
    @Autowired dev.genericpayments.platform.shared.application.port.out.DigestPort digest;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    double metric(String name){return meters.find(name).counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();}
    @Test void metricsCountCommittedFactsAndNoopProcessingWithoutDuplicateTransitions() {
        persisted();double received=metric("payment.inbox.received"),duplicate=metric("payment.inbox.duplicate"),processed=metric("payment.inbox.processed"),transitions=metric("payment.transition");
        process("evt","PAYMENT_SUCCEEDED");inbox.receive(envelope("evt","PAYMENT_SUCCEEDED","object-1",Map.of()));process("noop","PAYMENT_SUCCEEDED");
        assertThat(metric("payment.inbox.received")-received).isEqualTo(2);assertThat(metric("payment.inbox.duplicate")-duplicate).isEqualTo(1);assertThat(metric("payment.inbox.processed")-processed).isEqualTo(2);assertThat(metric("payment.transition")-transitions).isEqualTo(1);
        assertThat(meters.find("payment.inbox.lag").timers()).allSatisfy(t -> assertThat(t.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0));
    }
    @Test void failedKernelEmitsFailureOnlyAndNoTransitionBeforeRetry() {
        persisted();double failed=metric("payment.inbox.failed"),transition=metric("payment.transition");rejectJournal(PaymentEventType.PAYMENT_SUCCEEDED);process("evt","PAYMENT_SUCCEEDED");
        assertThat(metric("payment.inbox.failed")-failed).isEqualTo(1);assertThat(metric("payment.transition")-transition).isZero();assertThat(jdbc.queryForObject("select error_category from provider_inbox",String.class)).isEqualTo("PROCESSING_FAILED");
        removeJournalFailure();dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(INITIAL_TIME.plusSeconds(5));worker.runOnce();assertThat(metric("payment.transition")-transition).isEqualTo(1);
    }
    @Test void terminalAttemptRemainsImmutableWhenLatestApplicableAttemptChanges() {
        var p=persisted();var terminal=attemptSave.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"old","CARD",INITIAL_TIME).failed("DECLINED",INITIAL_TIME),INITIAL_TIME);
        var latest=attemptSave.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),2,"latest","CARD",INITIAL_TIME).unknown("UNKNOWN",INITIAL_TIME),INITIAL_TIME);process("evt","PAYMENT_SUCCEEDED");
        assertThat(attempts.findById(site,terminal.value().id()).orElseThrow()).isEqualTo(terminal);assertThat(attempts.findById(site,latest.value().id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }
    @ParameterizedTest @ValueSource(strings={"email","token","authorization","card_number","credentials"})
    void unsafeEnvelopeCannotInsert(String name) {
        assertThatThrownBy(() -> inbox.receive(envelope("evt","PAYMENT_SUCCEEDED","object-1",Map.of(name,"unsafe")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("provider_inbox")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"user@example.test","sk_test_12345678901234567890","4242424242424242"})
    void sensitiveValuesCannotInsert(String value) {
        assertThatThrownBy(() -> inbox.receive(envelope("evt","PAYMENT_SUCCEEDED","object-1",Map.of("value",value)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("provider_inbox")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"Basic dXNlcjpwYXNzd29yZA==","NL91ABNA0417164300"})
    void closedSchemaRejectsSecretsUnderInnocentKeyBeforeInsertion(String value) {
        assertThatThrownBy(() -> inbox.receive(envelope("unsafe","PAYMENT_SUCCEEDED","object-1",Map.of("state",value)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void closedSchemaRejectsUnknownKeysAndInvalidNormalizedShapes() {
        for(var payload:List.of(Map.of("note","ordinary"),Map.of("payment_id","not-a-uuid"),Map.of("payment_id","AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),Map.of("schema_version",1L),Map.of("schema_version",2),Map.of("state","SUCCEEDED"))) {
            assertThatThrownBy(() -> inbox.receive(envelope("invalid","PAYMENT_PROCESSING","object-1",payload))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void normalizedFakeFactAndProviderCounterCommitAtomically() {
        var p=save.save(payment());var req=request(p);state.prepare(req);
        var before=jdbc.queryForMap("select * from fake_provider_objects");
        jdbc.execute("create function task7_fact_reject() returns trigger language plpgsql as $$ begin raise exception 'injected fact failure'; end; $$");
        jdbc.execute("create trigger task7_fact_reject before insert on provider_inbox for each row execute function task7_fact_reject()");
        try {
            assertThatThrownBy(() -> state.enqueuePaymentEvent(req,1,ProviderState.Status.SUCCEEDED,INITIAL_TIME)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForMap("select * from fake_provider_objects")).isEqualTo(before);assertThat(count("provider_inbox")).isZero();
        } finally { jdbc.execute("drop trigger task7_fact_reject on provider_inbox");jdbc.execute("drop function task7_fact_reject()"); }
        state.enqueuePaymentEvent(req,1,ProviderState.Status.SUCCEEDED,INITIAL_TIME);
        assertThat(jdbc.queryForObject("select safe_payload::text from provider_inbox",String.class)).contains(p.id().value().toString(),"SUCCEEDED","schema_version");
        assertThat(jdbc.queryForObject("select event_counter from fake_provider_objects",Long.class)).isEqualTo(1);
    }
    @Test void successAfterProcessingAndDelayedProcessingCannotRegress() {
        var p=persisted(); process("processing","PAYMENT_PROCESSING"); process("success","PAYMENT_SUCCEEDED");
        var confirmed=load.findById(site,p.id()).orElseThrow(); process("late","PAYMENT_PROCESSING"); process("repeat","PAYMENT_SUCCEEDED");
        assertThat(load.findById(site,p.id()).orElseThrow()).isEqualTo(confirmed);
        assertThat(events.findByPayment(site,p.id())).extracting(PaymentEvent::type).containsExactly(PaymentEventType.PAYMENT_PROCESSING,PaymentEventType.PAYMENT_SUCCEEDED);
        assertThat(jdbc.queryForList("select status from provider_inbox",String.class)).containsOnly("PROCESSED");
        assertThat(count("outbox_events")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"PAYMENT_FAILED","PAYMENT_CANCELLED","PAYMENT_SUCCEEDED"})
    void firstTerminalFactWins(String terminal) {
        var p=persisted(); process("first",terminal); var first=load.findById(site,p.id()).orElseThrow();
        process("fail","PAYMENT_FAILED");process("cancel","PAYMENT_CANCELLED");process("success","PAYMENT_SUCCEEDED");
        assertThat(load.findById(site,p.id()).orElseThrow()).isEqualTo(first);assertThat(count("payment_events")).isEqualTo(1);
    }
    @Test void newestNonterminalAttemptResolvesUnknownAndOlderAttemptRemainsUntouched() {
        var p=persisted();
        var older=attemptSave.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"attempt-old","CARD",INITIAL_TIME),INITIAL_TIME);
        var latest=attemptSave.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),2,"attempt-new","CARD",INITIAL_TIME).unknown("UNKNOWN",INITIAL_TIME),INITIAL_TIME);
        process("success","PAYMENT_SUCCEEDED");
        assertThat(attempts.findById(site,latest.value().id()).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempts.findById(site,older.value().id()).orElseThrow()).isEqualTo(older);
    }
    @Test void activeCallbackSnapshotsSafePayloadWithJournalIdentity() {
        var p=persisted();callback(true);process("success","PAYMENT_SUCCEEDED");
        var row=jdbc.queryForMap("select * from outbox_events"); var event=events.findByPayment(site,p.id()).getFirst();
        assertThat(row.get("id")).isEqualTo(event.id());assertThat(row.get("endpoint_url")).isEqualTo("https://callback.test/exact");
        assertThat(row.get("status")).isEqualTo("PENDING"); assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("payload").toString()).contains("payment.succeeded",p.id().value().toString()).doesNotContain("object-1","metadata","secret","token","provider");
        process("repeat","PAYMENT_SUCCEEDED");assertThat(count("outbox_events")).isEqualTo(1);
    }
    @Test void disabledCallbackCreatesOnlyJournal() { persisted();callback(false);process("success","PAYMENT_SUCCEEDED");assertThat(count("payment_events")).isEqualTo(1);assertThat(count("outbox_events")).isZero(); }
    @Test void unresolvedObjectFailsAndIsRetriable() {
        inbox.receive(envelope("evt","PAYMENT_SUCCEEDED","object-1",Map.of()));worker.runOnce();assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("FAILED");
        var p=persisted();dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(INITIAL_TIME.plusSeconds(5));worker.runOnce();assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("select attempt_count from provider_inbox",Integer.class)).isEqualTo(2);
    }
    @Test void fullBatchOfOlderRefundFailuresCannotStarveLaterSuccess() {
        var p=persisted();
        for(int i=0;i<20;i++)inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),"refund-"+i,"object-1",PaymentEventType.REFUND_FAILED,INITIAL_TIME.minusSeconds(60),INITIAL_TIME.minusSeconds(30),Map.of()));
        worker.runOnce();
        assertThat(jdbc.queryForList("select error_category from provider_inbox",String.class)).hasSize(20).containsOnly("UNRESOLVED_OBJECT");
        inbox.receive(envelope("later-success","PAYMENT_SUCCEEDED","object-1",Map.of()));worker.runOnce();
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("select count(*) from provider_inbox where status='FAILED' and error_category='UNRESOLVED_OBJECT' and attempt_count=1",Integer.class)).isEqualTo(20);
    }
    @Test void retryWaitsForEligibilityThenRecoversAheadOfContinuingNewWork() {
        inbox.receive(envelope("retry","PAYMENT_SUCCEEDED","object-1",Map.of()));worker.runOnce();
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(INITIAL_TIME.plusSeconds(4));worker.runOnce();
        assertThat(jdbc.queryForObject("select attempt_count from provider_inbox",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_attempt_at from provider_inbox",java.sql.Timestamp.class).toInstant()).isEqualTo(INITIAL_TIME.plusSeconds(5));
        var p=persisted();
        for(int i=0;i<20;i++)inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),"new-"+i,"object-1",PaymentEventType.PAYMENT_SUCCEEDED,INITIAL_TIME,INITIAL_TIME.plusSeconds(6),Map.of()));
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(INITIAL_TIME.plusSeconds(6));worker.runOnce();
        assertThat(jdbc.queryForObject("select status from provider_inbox where provider_event_id='retry'",String.class)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select attempt_count from provider_inbox where provider_event_id='retry'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select received_at from provider_inbox where provider_event_id='retry'",java.sql.Timestamp.class).toInstant()).isEqualTo(INITIAL_TIME);
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("select count(*) from provider_inbox where status='PENDING'",Integer.class)).isEqualTo(1);
    }
    @Test void retryBackoffIsDurableDeterministicAndCapped() {
        persisted();inbox.receive(envelope("refund","REFUND_FAILED","object-1",Map.of()));
        var now=INITIAL_TIME;int attempts=0;
        for(int seconds:new int[]{5,10,20,40,80,160,300,300}) {
            dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(now);worker.runOnce();attempts++;
            var eligible=jdbc.queryForObject("select next_attempt_at from provider_inbox",java.sql.Timestamp.class).toInstant();
            assertThat(eligible).isEqualTo(now.plusSeconds(seconds));
            dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(eligible.minusMillis(1));worker.runOnce();
            assertThat(jdbc.queryForObject("select attempt_count from provider_inbox",Integer.class)).isEqualTo(attempts);
            assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select error_category from provider_inbox",String.class)).isEqualTo("UNRESOLVED_OBJECT");now=eligible;
        }
        assertThat(jdbc.queryForObject("select received_at from provider_inbox",java.sql.Timestamp.class).toInstant()).isEqualTo(INITIAL_TIME);
    }
    @Test void ambiguousReferenceFailsClosed() {
        persisted();save.save(payment().withProviderPaymentReference("object-1",INITIAL_TIME));process("evt","PAYMENT_SUCCEEDED");assertThat(count("payment_events")).isZero();assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("FAILED");
    }
    @ParameterizedTest @ValueSource(strings={"REFUND_REQUESTED","REFUND_PROCESSING","REFUND_SUCCEEDED","REFUND_FAILED"})
    void refundFactsRequireExactRefundOwnershipNotPaymentReference(String type) { persisted();process("refund",type);assertThat(count("payment_events")).isZero();assertThat(jdbc.queryForObject("select error_category from provider_inbox",String.class)).isEqualTo("UNRESOLVED_OBJECT"); }
    @ParameterizedTest @ValueSource(strings={"payments","payment_attempts","payment_events","outbox_events","provider_inbox"})
    void anyKernelWriteFailureRollsBackAllBusinessFactsAndRetries(String table) {
        var p=persisted();callback(true);attemptSave.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"attempt","CARD",INITIAL_TIME),INITIAL_TIME);
        inbox.receive(envelope("evt","PAYMENT_SUCCEEDED","object-1",Map.of()));
        var before=jdbc.queryForMap("select * from payment_attempts");
        jdbc.execute("create or replace function task7_reject() returns trigger language plpgsql as $$ begin if TG_TABLE_NAME<>'provider_inbox' or new.status='PROCESSED' then raise exception 'injected failure'; end if; return new; end; $$");
        jdbc.execute("create trigger task7_reject before insert or update on "+table+" for each row execute function task7_reject()");
        try { worker.runOnce();assertThat(load.findById(site,p.id()).orElseThrow()).isEqualTo(p);assertThat(jdbc.queryForMap("select * from payment_attempts")).isEqualTo(before);assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("FAILED"); }
        finally { jdbc.execute("drop trigger task7_reject on "+table);jdbc.execute("drop function task7_reject()"); }
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(INITIAL_TIME.plusSeconds(5));worker.runOnce();assertThat(count("payment_events")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(1);
    }
}
