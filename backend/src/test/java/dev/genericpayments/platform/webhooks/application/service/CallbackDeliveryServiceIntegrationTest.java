package dev.genericpayments.platform.webhooks.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.payments.application.port.out.PaymentOutboxPort;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dev.genericpayments.platform.shared.application.port.out.*;
class CallbackDeliveryServiceIntegrationTest extends CheckoutTestFixture {
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired OutboxPort outbox;
    @Autowired PaymentOutboxPort snapshots;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EncryptedSecretPort encryption;
    @Autowired CallbackPayloadPort payload;
    @Autowired Stage2TelemetryPort telemetry;
    @Autowired Clock clock;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired org.springframework.boot.availability.ApplicationAvailability availability;
    UUID event() {
        callbacks.configure(site.value(),"https://example.com/callback",true);
        var p=save.save(payment().succeeded(INITIAL_TIME));
        var e=eventsFor(p);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> snapshots.snapshot(p,e,INITIAL_TIME));return e.id();
    }
    PaymentEvent eventsFor(Payment p) {
        return jdbcEvent(p);
    }
    PaymentEvent jdbcEvent(Payment p) {
        return new TransactionTemplate(transactions).execute(tx -> applicationEvents.append(p.id(),seq -> new PaymentEvent(UUID.randomUUID(),p.id(),seq,site,account.id(),PaymentEventType.PAYMENT_SUCCEEDED,PaymentEventSource.PROVIDER_EVENT,p.money(),null,null,INITIAL_TIME,INITIAL_TIME,"request-8",null,"a".repeat(64),Map.of())));
    }
    @Autowired dev.genericpayments.platform.payments.application.port.out.AppendPaymentEventPort applicationEvents;
    @Test void sixFailuresScheduleApprovedDelaysThenDead() {
        var readiness=availability.getReadinessState();
        UUID id=event();Instant now=INITIAL_TIME;int attempt=0;
        for(long delay:new long[]{60,300,1800,7200,43200,0}) {
            var claim=outbox.claimAutomatic("worker",1,now,Duration.ofSeconds(30)).getFirst();assertThat(claim.attempt()).isEqualTo(++attempt);
            assertThat(outbox.complete(claim,CallbackHttpPort.Result.failed(null,CallbackHttpPort.ErrorCategory.NETWORK),now)).isTrue();
            var row=jdbc.queryForMap("select * from outbox_events where id=?",id);
            assertThat(row.get("status")).isEqualTo(attempt==6?"DEAD":"PENDING");
            if(attempt<6){now=now.plusSeconds(delay);assertThat(((java.sql.Timestamp)row.get("next_attempt_at")).toInstant()).isEqualTo(now);}
        }
        assertThat(count("outbox_deliveries")).isEqualTo(6);
        assertThat(availability.getReadinessState()).isEqualTo(readiness);
    }
    @Test void liveLeaseExcludesOtherWorkersAndExpiredLeaseAllocatesNewAttempt() {
        UUID id=event();var first=outbox.claimAutomatic("first",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        assertThat(outbox.claimAutomatic("second",1,INITIAL_TIME,Duration.ofSeconds(30))).isEmpty();
        var second=outbox.claimAutomatic("second",1,INITIAL_TIME.plusSeconds(30),Duration.ofSeconds(30)).getFirst();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(outbox.complete(first,CallbackHttpPort.Result.success(200),INITIAL_TIME.plusSeconds(31))).isFalse();
        assertThat(outbox.complete(second,CallbackHttpPort.Result.success(204),INITIAL_TIME.plusSeconds(31))).isTrue();
        assertThat(outbox.complete(second,CallbackHttpPort.Result.success(204),INITIAL_TIME.plusSeconds(31))).isFalse();
        assertThat(jdbc.queryForObject("select status from outbox_events where id=?",String.class,id)).isEqualTo("DELIVERED");assertThat(count("outbox_deliveries")).isEqualTo(1);
    }
    @Test void manualRequestFreezesSelectionAndUsesGlobalAttemptWithoutAutomaticSchedule() {
        UUID id=event();assertThatThrownBy(()->outbox.enqueue(id,OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME)).isInstanceOf(RuntimeException.class);
        jdbc.update("update outbox_events set status='DEAD',attempt_count=6 where id=?",id);
        var selected=outbox.enqueue(id,OutboxPort.Mode.CURRENT_CONFIG_RETRY,INITIAL_TIME);
        callbacks.configure(site.value(),"https://example.com/new",true);callbacks.rotate(site.value());
        var claim=outbox.claimManual("manual",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        assertThat(claim.endpoint()).isEqualTo("https://example.com/callback");assertThat(claim.secretVersion()).isEqualTo(1);assertThat(claim.attempt()).isEqualTo(7);
        assertThat(outbox.complete(claim,CallbackHttpPort.Result.failed(500,CallbackHttpPort.ErrorCategory.HTTP_STATUS),INITIAL_TIME)).isTrue();
        assertThat(outbox.retry(selected.id()).status()).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select status from outbox_events where id=?",String.class,id)).isEqualTo("DEAD");
        assertThat(outbox.claimManual("manual",1,INITIAL_TIME.plusSeconds(9999),Duration.ofSeconds(30))).isEmpty();
        outbox.enqueue(id,OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME);var next=outbox.claimManual("manual",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();assertThat(next.attempt()).isEqualTo(8);
        outbox.complete(next,CallbackHttpPort.Result.success(200),INITIAL_TIME);assertThat(jdbc.queryForObject("select status from outbox_events where id=?",String.class,id)).isEqualTo("DELIVERED");
    }
    @Test void deliverySignsTheSameCanonicalBytesItTransmitsOutsideTransactionsAndRejectsAmbientCalls() {
        UUID id=event();var claim=outbox.claimAutomatic("network",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        var delivery=new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,body)->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(headers).containsEntry("Payment-Event-Id",id.toString()).containsEntry("Payment-Timestamp",Long.toString(INITIAL_TIME.getEpochSecond())).containsEntry("Payment-Secret-Version","1").containsEntry("Content-Type","application/json");
            assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(java.util.Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret())),INITIAL_TIME.getEpochSecond(),body));
            assertThat(new String(body,java.nio.charset.StandardCharsets.UTF_8)).contains("payment.succeeded").doesNotContain("secret","token","provider");return CallbackHttpPort.Result.success(204);
        },clock);
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->delivery.deliver(claim))).isInstanceOf(RuntimeException.class);
        delivery.deliver(claim);assertThat(jdbc.queryForObject("select status from outbox_events where id=?",String.class,id)).isEqualTo("DELIVERED");
    }
    @Test void unknownPayloadFieldsFailWithoutNetworkAndKeepSafeCategory() {
        UUID id=event();var claim=outbox.claimAutomatic("network",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        var malformed=new OutboxPort.Claim(claim.eventId(),null,claim.siteId(),claim.endpoint(),claim.secretVersion(),claim.secret(),"{\"email\":\"hidden@example.com\"}",claim.attempt(),claim.owner(),claim.startedAt(),claim.createdAt(),claim.mode(),claim.context());
        new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,body)->{throw new AssertionError("malformed payload sent");},clock).deliver(malformed);
        assertThat(jdbc.queryForObject("select error_category from outbox_deliveries where outbox_event_id=?",String.class,id)).isEqualTo("PAYLOAD");
    }
    @Test void pendingGaugeAndCommittedOutcomesUseTrustedContext() {
        UUID id=event();double retried=metric("payment.outbox.retried"),delivered=metric("payment.outbox.delivered");
        var first=outbox.claimAutomatic("telemetry",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        assertThat(metrics.get("payment.outbox.pending").tags("site","test-site","provider","fake").gauge().value()).isEqualTo(1);
        outbox.complete(first,CallbackHttpPort.Result.failed(500,CallbackHttpPort.ErrorCategory.HTTP_STATUS),INITIAL_TIME);
        assertThat(metric("payment.outbox.retried")).isEqualTo(retried+1);
        var second=outbox.claimAutomatic("telemetry",1,INITIAL_TIME.plusSeconds(60),Duration.ofSeconds(30)).getFirst();outbox.complete(second,CallbackHttpPort.Result.success(200),INITIAL_TIME.plusSeconds(65));
        assertThat(metric("payment.outbox.delivered")).isEqualTo(delivered+1);assertThat(metrics.get("payment.outbox.pending").tags("site","test-site","provider","fake").gauge().value()).isZero();
        assertThat(metrics.get("payment.outbox.lag").tags("site","test-site","provider","fake").timer().count()).isPositive();
        var emptyTelemetry=new dev.genericpayments.platform.shared.adapter.out.observability.MicrometerStage2TelemetryAdapter(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),Set.of());
        var isolated=new dev.genericpayments.platform.webhooks.adapter.out.persistence.OutboxPersistenceAdapter(jdbc,emptyTelemetry);
        jdbc.update("update outbox_events set status='DEAD' where id=?",id);
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->isolated.enqueue(id,OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("outbox_retry_requests")).isZero();
    }
    double metric(String name){var meter=metrics.find(name).tags("site","test-site","provider","fake").counter();return meter==null?0:meter.count();}
    @Test void cleanupRetainsQueuedAndInflightManualSecretAndCurrentRetrySurvivesOriginalTombstone() {
        UUID id=event();jdbc.update("update outbox_events set status='DEAD' where id=?",id);
        outbox.enqueue(id,OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME);callbacks.rotate(site.value());
        assertThat(callbacks.cleanup(INITIAL_TIME.plusSeconds(86400))).isZero();
        var claim=outbox.claimManual("manual",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();assertThat(callbacks.cleanup(INITIAL_TIME.plusSeconds(86400))).isZero();
        outbox.complete(claim,CallbackHttpPort.Result.failed(null,CallbackHttpPort.ErrorCategory.NETWORK),INITIAL_TIME);assertThat(callbacks.cleanup(INITIAL_TIME.plusSeconds(86400))).isEqualTo(1);
        assertThatThrownBy(()->outbox.enqueue(id,OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME)).isInstanceOf(RuntimeException.class);
        outbox.enqueue(id,OutboxPort.Mode.CURRENT_CONFIG_RETRY,INITIAL_TIME);assertThat(outbox.claimManual("current",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst().secretVersion()).isEqualTo(2);
    }
    @Test void malformedScalarPayloadShapesNeverProduceCallbackBytes() {
        event();var claim=outbox.claimAutomatic("shape",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        for(String stored:List.of(claim.payload().replace("\"schema_version\": 1","\"schema_version\": 4294967297"),claim.payload().replace("\"EUR\"","\"ZZZ\""),claim.payload().replace("\"amount_minor\": 2000","\"amount_minor\": 2.5"))) {
            assertThatThrownBy(()->payload.bytes(new OutboxPort.Claim(claim.eventId(),null,claim.siteId(),claim.endpoint(),claim.secretVersion(),claim.secret(),stored,claim.attempt(),claim.owner(),claim.startedAt(),claim.createdAt(),claim.mode(),claim.context()))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void concurrentAutomaticWorkersAllocateExclusiveClaimsAndSkipLockedRows() throws Exception {
        UUID first=event(),second=event();var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var holder=executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{jdbc.queryForList("select id from outbox_events where id=? for update",first);locked.countDown();await(release);}));
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var skipped=executor.submit(()->outbox.claimAutomatic("skip",2,INITIAL_TIME,Duration.ofSeconds(30))).get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(skipped).extracting(OutboxPort.Claim::eventId).containsExactly(second);release.countDown();holder.get(5,java.util.concurrent.TimeUnit.SECONDS);
            var gate=new java.util.concurrent.CountDownLatch(1);
            var a=executor.submit(()->{await(gate);return outbox.claimAutomatic("one",1,INITIAL_TIME,Duration.ofSeconds(30));});
            var b=executor.submit(()->{await(gate);return outbox.claimAutomatic("two",1,INITIAL_TIME,Duration.ofSeconds(30));});gate.countDown();
            var combined=new ArrayList<OutboxPort.Claim>();combined.addAll(a.get(5,java.util.concurrent.TimeUnit.SECONDS));combined.addAll(b.get(5,java.util.concurrent.TimeUnit.SECONDS));assertThat(combined).hasSize(1);assertThat(combined.getFirst().eventId()).isEqualTo(first);assertThat(combined.getFirst().attempt()).isEqualTo(1);
        }finally{release.countDown();}
    }
    @Test void manualClaimsExcludeConcurrentWorkersAndRecoverExpiredLeasesWithoutReusingAttempt() throws Exception {
        UUID id=event();jdbc.update("update outbox_events set status='DEAD',attempt_count=6 where id=?",id);var request=outbox.enqueue(id,OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CountDownLatch(1);var a=executor.submit(()->{await(gate);return outbox.claimManual("one",1,INITIAL_TIME,Duration.ofSeconds(30));});var b=executor.submit(()->{await(gate);return outbox.claimManual("two",1,INITIAL_TIME,Duration.ofSeconds(30));});gate.countDown();
            var claims=new ArrayList<OutboxPort.Claim>();claims.addAll(a.get(5,java.util.concurrent.TimeUnit.SECONDS));claims.addAll(b.get(5,java.util.concurrent.TimeUnit.SECONDS));assertThat(claims).hasSize(1);var first=claims.getFirst();assertThat(first.attempt()).isEqualTo(7);
            var recovered=outbox.claimManual("recovered",1,INITIAL_TIME.plusSeconds(30),Duration.ofSeconds(30)).getFirst();assertThat(recovered.attempt()).isEqualTo(8);assertThat(recovered.requestId()).isEqualTo(request.id());
            assertThat(outbox.complete(first,CallbackHttpPort.Result.success(200),INITIAL_TIME.plusSeconds(31))).isFalse();assertThat(outbox.complete(recovered,CallbackHttpPort.Result.success(204),INITIAL_TIME.plusSeconds(31))).isTrue();assertThat(outbox.complete(recovered,CallbackHttpPort.Result.success(204),INITIAL_TIME.plusSeconds(31))).isFalse();assertThat(count("outbox_deliveries")).isEqualTo(1);
        }
    }
    @Test void cleanupSerializesAgainstUncommittedManualReferenceAndNeverDestroysItsSecret() throws Exception {
        UUID id=event();jdbc.update("update outbox_events set status='DEAD' where id=?",id);callbacks.rotate(site.value());var inserted=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var enqueue=executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{outbox.enqueue(id,OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME);inserted.countDown();await(release);}));
            assertThat(inserted.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var cleanup=executor.submit(()->{try{return callbacks.cleanup(INITIAL_TIME.plusSeconds(86400));}catch(org.springframework.dao.DataIntegrityViolationException guarded){return 0;}});
            release.countDown();enqueue.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThat(cleanup.get(5,java.util.concurrent.TimeUnit.SECONDS)).isZero();
            assertThat(jdbc.queryForObject("select destroyed_at is null from site_callback_secret_versions where site_id=? and secret_version=1",Boolean.class,site.value())).isTrue();assertThat(count("outbox_retry_requests")).isEqualTo(1);
        }finally{release.countDown();}
    }
    private static void await(java.util.concurrent.CountDownLatch latch){try{if(!latch.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Coordination timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
    @Test void durableRetryFactsAndSiteOwnershipRemainDatabaseGuarded() {
        UUID id=event();jdbc.update("update outbox_events set status='DEAD' where id=?",id);var retry=outbox.enqueue(id,OutboxPort.Mode.SNAPSHOT_RETRY,INITIAL_TIME);
        assertThatThrownBy(()->jdbc.update("update outbox_retry_requests set result='SUCCEEDED' where id=?",retry.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update outbox_retry_requests set endpoint_url='https://example.com/changed' where id=?",retry.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("insert into outbox_retry_requests(id,outbox_event_id,site_id,delivery_mode,endpoint_url,secret_version_id,created_at) select ?,outbox_event_id,?,delivery_mode,endpoint_url,secret_version_id,created_at from outbox_retry_requests where id=?",UUID.randomUUID(),UUID.randomUUID(),retry.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var claim=outbox.claimManual("immutable",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();outbox.complete(claim,CallbackHttpPort.Result.success(200),INITIAL_TIME);
        assertThatThrownBy(()->jdbc.update("update outbox_retry_requests set status='PENDING',completed_at=null,result=null where id=?",retry.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void approvedRefundPayloadCarriesPaymentProjectionStatusNotRefundStatus() {
        event();var claim=outbox.claimAutomatic("refund-shape",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        String stored=claim.payload().replace("payment.succeeded","refund.succeeded").replace("SUCCEEDED","PARTIALLY_REFUNDED");
        assertThat(new String(payload.bytes(new OutboxPort.Claim(claim.eventId(),null,claim.siteId(),claim.endpoint(),claim.secretVersion(),claim.secret(),stored,claim.attempt(),claim.owner(),claim.startedAt(),claim.createdAt(),claim.mode(),claim.context())),java.nio.charset.StandardCharsets.UTF_8)).contains("PARTIALLY_REFUNDED","refund.succeeded");
    }
}
