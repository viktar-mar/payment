package dev.genericpayments.platform.webhooks.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import java.time.*;
import java.sql.Timestamp;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCallbackPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionEventStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
class SubscriptionCallbackIntegrationTest extends SubscriptionFactFixture {
    @Autowired CallbackConfigurationPort config;@Autowired CallbackPayloadPort payload;@Autowired OutboxPort outbox;
    @Autowired SubscriptionCallbackPort callbacks;
    @Autowired SubscriptionEventStore events;
    @Autowired EncryptedSecretPort encryption;

    /** Hosted CI only: this fixture starts Spring/PostgreSQL; never select it in local pure runs. */
    @Test void futureBillingSnapshotEnqueuesImmediatelyWhileLeaseRetryAndSigningStayUtc() throws Exception {
        price=price(90);var r=start();apply(r.subscriptionId(),retrieve(r),"long-trial");
        var subscription=sub(r.subscriptionId());var utc=INITIAL_TIME;var billing=utc.plus(Duration.ofDays(35));
        var reveal=config.configure(site.value(),"https://example.com/callback",true);
        var access=new SubscriptionAccessPolicy().evaluate(subscription,billing);
        assertThat(access.allowed()).isTrue();
        var event=new SubscriptionEvent(UUID.randomUUID(),site,subscription.id(),account.id(),0,subscription.revision(),SubscriptionEvent.Type.CONFIRMED,SubscriptionEvent.Source.RECONCILIATION,billing,utc,null,null,null,null,"future-billing-callback","billing-boundary");
        assertThatThrownBy(()->tx.required(()->{
            assertThat(events.append(event)).isTrue();callbacks.snapshot(subscription,event,access,utc);
            throw new IllegalStateException("rollback future snapshot");
        })).hasMessage("rollback future snapshot");
        assertThat(count("outbox_events")).isZero();
        assertThat(events.list(site,subscription.id(),100,0)).noneMatch(e->e.id().equals(event.id()));
        tx.required(()->{assertThat(events.append(event)).isTrue();callbacks.snapshot(subscription,event,access,utc);return null;});
        var stored=jdbc.queryForMap("select payload::text,next_attempt_at,created_at,updated_at from outbox_events where id=?",event.id());
        tx.required(()->{if(events.append(event))callbacks.snapshot(subscription,event,access,utc.plusSeconds(1));return null;});
        assertThat(jdbc.queryForMap("select payload::text,next_attempt_at,created_at,updated_at from outbox_events where id=?",event.id())).isEqualTo(stored);
        for(var column:List.of("next_attempt_at","created_at","updated_at"))assertThat(((Timestamp)stored.get(column)).toInstant()).isEqualTo(utc);
        var journal=events.list(site,subscription.id(),100,0).stream().filter(e->e.id().equals(event.id())).findFirst().orElseThrow();
        assertThat(journal.occurredAt()).isEqualTo(billing);assertThat(journal.receivedAt()).isEqualTo(utc);

        var claims=outbox.claimAutomatic("first-utc",1,utc,Duration.ofSeconds(30));assertThat(claims).hasSize(1);
        var first=claims.getFirst();assertThat(first.createdAt()).isEqualTo(utc);
        var bytes=payload.bytes(first);var mapper=new ObjectMapper();
        var expected=new TreeMap<String,Object>();
        expected.put("schema_version",4);expected.put("event_id",event.id().toString());expected.put("event_type","subscription.updated");expected.put("site_id",site.value().toString());
        expected.put("subscription_id",subscription.id().toString());expected.put("customer_id",customer.id().value().toString());expected.put("product_id",subscription.productId().value().toString());expected.put("revision",subscription.revision());
        expected.put("state","TRIALING");expected.put("access_allowed",true);expected.put("access_valid_until",billing.plusSeconds(300).toString());expected.put("occurred_at",billing.toString());expected.put("billing_record_id",null);expected.put("payment_id",null);
        assertThat(bytes).isEqualTo(mapper.writeValueAsBytes(expected));
        // A later billing evaluation leaves the live UTC lease and retry window alone.
        assertThat(new SubscriptionAccessPolicy().evaluate(subscription,billing.plus(Duration.ofDays(1))).allowed()).isTrue();
        assertThat(outbox.claimAutomatic("before-lease",1,utc.plusSeconds(29),Duration.ofSeconds(30))).isEmpty();
        var reclaimed=outbox.claimAutomatic("reclaimed-utc",1,utc.plusSeconds(30),Duration.ofSeconds(30));assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.getFirst().attempt()).isEqualTo(2);
        assertThat(outbox.complete(first,CallbackHttpPort.Result.success(204),utc.plusSeconds(30))).isFalse();
        byte[] secret=Base64.getUrlDecoder().decode(reveal.secret());
        var posts=new ArrayList<Instant>();
        try {
            var failed=new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,body)->{
                assertThat(endpoint).isEqualTo("https://example.com/callback");assertThat(body).isEqualTo(bytes);
                assertThat(headers.get("Payment-Timestamp")).isEqualTo(Long.toString(utc.plusSeconds(30).getEpochSecond()));
                assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(secret,utc.plusSeconds(30).getEpochSecond(),body));
                posts.add(utc.plusSeconds(30));return CallbackHttpPort.Result.failed(null,CallbackHttpPort.ErrorCategory.NETWORK);
            },Clock.fixed(utc.plusSeconds(30),ZoneOffset.UTC));
            failed.deliver(reclaimed.getFirst());assertThat(posts).containsExactly(utc.plusSeconds(30));
            assertThat(jdbc.queryForObject("select next_attempt_at from outbox_events where id=?",Timestamp.class,event.id()).toInstant()).isEqualTo(utc.plusSeconds(330));
            assertThat(outbox.claimAutomatic("before-retry",1,utc.plusSeconds(329),Duration.ofSeconds(30))).isEmpty();
            var retry=outbox.claimAutomatic("retry-utc",1,utc.plusSeconds(330),Duration.ofSeconds(30));assertThat(retry).hasSize(1);
            assertThat(retry.getFirst().payload()).isEqualTo(first.payload());
            var success=new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,body)->{
                assertThat(body).isEqualTo(bytes);assertThat(headers.get("Payment-Timestamp")).isEqualTo(Long.toString(utc.plusSeconds(330).getEpochSecond()));
                assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(secret,utc.plusSeconds(330).getEpochSecond(),body));
                posts.add(utc.plusSeconds(330));return CallbackHttpPort.Result.success(204);
            },Clock.fixed(utc.plusSeconds(330),ZoneOffset.UTC));
            success.deliver(retry.getFirst());assertThat(posts).containsExactly(utc.plusSeconds(30),utc.plusSeconds(330));
        } finally {Arrays.fill(secret,(byte)0);}
        assertThat(jdbc.queryForObject("select status from outbox_events where id=?",String.class,event.id())).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select updated_at from outbox_events where id=?",Timestamp.class,event.id()).toInstant()).isEqualTo(utc.plusSeconds(330));
        assertThat(jdbc.queryForList("select finished_at from outbox_deliveries where outbox_event_id=? order by attempt_number",Timestamp.class,event.id())).containsExactly(Timestamp.from(utc.plusSeconds(30)),Timestamp.from(utc.plusSeconds(330)));
    }

    @Test void failedCashFactRollsBackBothCallbackFamiliesAndRetryDeduplicates() {
        config.configure(site.value(),"https://example.com/callback",true);
        var r=start();var snapshot=retrieve(r);
        assertThatThrownBy(()->tx.required(()->{
            apply(r.subscriptionId(),snapshot,"failed-fact");
            assertThat(jdbc.queryForList("select (payload->>'schema_version')::int from outbox_events",Integer.class)).containsExactlyInAnyOrder(3,4);
            throw new IllegalStateException("injected fact rollback");
        })).hasMessage("injected fact rollback");
        for(var table:List.of("payments","payment_events","subscription_billing_records","subscription_events","outbox_events"))assertThat(count(table)).as(table).isZero();
        assertThat(sub(r.subscriptionId()).paidThrough()).isNull();
        apply(r.subscriptionId(),snapshot,"retry");
        var originals=jdbc.queryForList("select id,payload,source_kind,payment_account_id from outbox_events order by id");
        apply(r.subscriptionId(),snapshot,"duplicate");
        assertThat(jdbc.queryForList("select id,payload,source_kind,payment_account_id from outbox_events order by id")).isEqualTo(originals);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(2);
    }
    private UUID trialEvent(){config.configure(site.value(),"https://example.com/callback",true);price=price(3);var r=start();apply(r.subscriptionId(),retrieve(r),"trial-callback");return jdbc.queryForObject("select id from outbox_events",UUID.class);}
    @Test void lifecycleSourceRequiresJournalIdentityAndAllSourceFactsAreImmutable(){
        var id=trialEvent();
        assertThatThrownBy(()->jdbc.update("insert into outbox_events(id,site_id,source_kind,subscription_id,subscription_event_id,payment_account_id,callback_type,payload,endpoint_url,secret_version_id,status,next_attempt_at,attempt_count,created_at,updated_at) select ?,site_id,source_kind,subscription_id,null,payment_account_id,callback_type,payload,endpoint_url,secret_version_id,status,next_attempt_at,0,created_at,updated_at from outbox_events where id=?",UUID.randomUUID(),id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for(String assignment:List.of("source_kind='PAYMENT'","subscription_id=null","subscription_event_id=null","payment_account_id=null","payload='{}'::jsonb"))assertThatThrownBy(()->jdbc.update("update outbox_events set "+assignment+" where id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void v4RejectsWrongScalarsNestedFieldsAndUnboundedGrants()throws Exception{
        trialEvent();var claim=outbox.claimAutomatic("shape",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();var mapper=new ObjectMapper();
        for(var invalid:List.of("revision-negative","revision-fraction","extra","state","boolean","missing-expiry","late-expiry","nested-id","wrong-id")){
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(claim.payload());
            switch(invalid){case "revision-negative"->node.put("revision",-1);case "revision-fraction"->node.put("revision",1.5);case "extra"->node.put("secret","forbidden");case "state"->node.put("state","RAW_PROVIDER_STATE");case "boolean"->node.put("access_allowed","true");case "missing-expiry"->node.putNull("access_valid_until");case "late-expiry"->node.put("access_valid_until",INITIAL_TIME.plusSeconds(301).toString());case "nested-id"->node.putObject("payment_id").put("nested",1);case "wrong-id"->node.put("event_id",UUID.randomUUID().toString());}
            var changed=new OutboxPort.Claim(claim.eventId(),claim.requestId(),claim.siteId(),claim.endpoint(),claim.secretVersion(),claim.secret(),mapper.writeValueAsString(node),claim.attempt(),claim.owner(),claim.startedAt(),claim.createdAt(),claim.mode(),claim.context());
            assertThatThrownBy(()->payload.bytes(changed)).as(invalid).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void lifecycleRotationDeadAndReplayNeverRefreshAccessOrAccount()throws Exception{
        var id=trialEvent();var original=jdbc.queryForObject("select payload::text from outbox_events where id=?",String.class,id);
        var replacement=UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'rotated','STRIPE','SANDBOX','ACTIVE','unused','unused',now(),0)",replacement);jdbc.update("update sites set payment_account_id=? where id=?",replacement,site.value());
        config.rotate(site.value());var now=INITIAL_TIME;
        for(long delay:new long[]{60,300,1800,7200,43200,0}){var claim=outbox.claimAutomatic("fail",1,now,Duration.ofSeconds(30)).getFirst();assertThat(claim.secretVersion()).isEqualTo(1);assertThat(claim.context().provider()).isEqualTo(dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Provider.FAKE);assertThat(claim.payload()).isEqualTo(original);payload.bytes(claim);assertThat(outbox.complete(claim,CallbackHttpPort.Result.failed(null,CallbackHttpPort.ErrorCategory.NETWORK),now)).isTrue();now=now.plusSeconds(delay);}
        assertThat(jdbc.queryForObject("select status from outbox_events where id=?",String.class,id)).isEqualTo("DEAD");
        outbox.enqueue(id,OutboxPort.Mode.CURRENT_CONFIG_RETRY,now);var replay=outbox.claimManual("replay",1,now,Duration.ofSeconds(30)).getFirst();assertThat(replay.secretVersion()).isEqualTo(2);assertThat(replay.payload()).isEqualTo(original);
        assertThat(new ObjectMapper().readTree(payload.bytes(replay)).path("access_valid_until").asText()).isEqualTo(INITIAL_TIME.plusSeconds(300).toString());assertThat(outbox.complete(replay,CallbackHttpPort.Result.success(204),now)).isTrue();
    }
    @Test void lifecycleAndCashSnapshotsAreAtomicPinnedAndHaveIndependentSourceRows()throws Exception{
        config.configure(site.value(),"https://example.com/callback",true);var r=start();apply(r.subscriptionId(),retrieve(r),"callback");
        assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(jdbc.queryForList("select source_kind from outbox_events",String.class)).containsExactlyInAnyOrder("PAYMENT","SUBSCRIPTION");
        assertThat(jdbc.queryForObject("select payment_id from outbox_events where source_kind='SUBSCRIPTION'",UUID.class)).isNull();
        var claims=outbox.claimAutomatic("test",20,INITIAL_TIME,Duration.ofSeconds(30));assertThat(claims).hasSize(2);
        for(var c:claims){var n=new ObjectMapper().readTree(payload.bytes(c));if(n.path("schema_version").asInt()==4){assertThat(n.path("access_allowed").asBoolean()).isTrue();assertThat(n.path("access_valid_until").asText()).isEqualTo(INITIAL_TIME.plusSeconds(300).toString());assertThat(n.path("state").asText()).isEqualTo("ACTIVE");assertThat(n.size()).isEqualTo(14);}else assertThat(n.path("schema_version").asInt()).isEqualTo(3);}
        assertThat(jdbc.queryForList("select payment_account_id from outbox_events",UUID.class)).containsOnly(account.id().value());
    }
}
