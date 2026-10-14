package dev.genericpayments.platform.webhooks.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
class SubscriptionCallbackIntegrationTest extends SubscriptionFactFixture {
    @Autowired CallbackConfigurationPort config;@Autowired CallbackPayloadPort payload;@Autowired OutboxPort outbox;
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
