package dev.genericpayments.platform.provider.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** HOSTED ONLY: inherits real application/PostgreSQL fixture and no-network client tripwire. */
class StripeBillingWebhookTest extends StripeWebhookAdmissionTest {
    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource source;
    static List<String> billingTypes(){return List.of("customer.subscription.created","customer.subscription.updated","customer.subscription.deleted","customer.subscription.paused","customer.subscription.resumed","customer.subscription.trial_will_end","invoice.created","invoice.updated","invoice.finalized","invoice.finalization_failed","invoice.paid","invoice.payment_failed","invoice.payment_action_required","invoice.voided","invoice.marked_uncollectible","setup_intent.succeeded","setup_intent.setup_failed","setup_intent.canceled","subscription_schedule.created","subscription_schedule.updated","subscription_schedule.released","subscription_schedule.completed","subscription_schedule.canceled","subscription_schedule.aborted");}
    static byte[] billingBody(String type){
        String kind=type.startsWith("customer.")?"subscription":type.substring(0,type.indexOf('.'));
        String id=switch(kind){case "subscription"->"sub_owned";case "invoice"->"in_owned";case "setup_intent"->"seti_owned";default->"sub_sched_owned";};
        return new String(body(type),StandardCharsets.UTF_8).replace("pi_fixture",id).replace("\"object\":\"payment_intent\"","\"object\":\""+kind+"\"").getBytes(StandardCharsets.UTF_8);
    }
    @ParameterizedTest @MethodSource("billingTypes")
    void eachBillingTypeCommitsOneDurableHintWithoutProviderOrFinancialEffects(String type)throws Exception{
        byte[] raw=billingBody(type);String sig=signature(raw,NOW.getEpochSecond(),SECRET);
        for(int i=0;i<2;i++)mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",sig).content(raw)).andExpect(status().isNoContent());
        assertThat(count("provider_inbox")).isEqualTo(1);assertThat(jdbc.queryForObject("select event_family from provider_inbox",String.class)).isEqualTo("SUBSCRIPTION");
        assertThat(jdbc.queryForObject("select safe_payload::text from provider_inbox",String.class)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("select count(*) from provider_inbox where reference_object_type is null and provider_event_type is null and provider_api_version is null",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select row_to_json(i)::text from provider_inbox i").toString()).doesNotContain("never-persist-this");
        for(String table:List.of("payment_events","outbox_events","payments","subscription_events","provider_operations"))assertThat(count(table)).as(table).isZero();
    }
    @Test void scheduleCheckIsNarrowAndLegacyReferenceShapeRemainsStrict()throws Exception{
        byte[] raw=billingBody("subscription_schedule.created");mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select event_type from provider_inbox",String.class)).isEqualTo("SUBSCRIPTION_SCHEDULE_CHANGED");
        for(String update:List.of("event_type='SUBSCRIPTION_UNKNOWN'","reference_object_type='PAYMENT'","provider_event_type='invoice.created'","provider_api_version='2026-09-30.endive'"))
            assertThatThrownBy(()->jdbc.update("update provider_inbox set "+update)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void billingInsertFailureReturns503AndRollsBackWithoutFinancialSideEffects()throws Exception{
        jdbc.execute("create function task6_reject_billing() returns trigger language plpgsql as $$ begin raise exception 'private-store-detail'; end; $$");
        jdbc.execute("create trigger task6_reject_billing before insert on provider_inbox for each row execute function task6_reject_billing()");
        try{byte[] raw=billingBody("invoice.created");mvc.perform(post(PATH).contentType("application/json").header("Stripe-Signature",signature(raw,NOW.getEpochSecond(),SECRET)).content(raw)).andExpect(status().isServiceUnavailable()).andExpect(r->assertThat(r.getResponse().getContentAsString()).doesNotContain("private-store-detail","never-persist-this"));assertThat(count("provider_inbox")).isZero();assertThat(count("payments")).isZero();}
        finally{jdbc.execute("drop trigger task6_reject_billing on provider_inbox");jdbc.execute("drop function task6_reject_billing()");}
    }
    @Test void populatedV27UpgradeAddsOnlyScheduleAdmissionAndKeepsExistingSnapshots()throws Exception{
        String schema="task6_schedule_"+UUID.randomUUID().toString().replace("-","");
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("27").load().migrate();
        var connection=new org.springframework.jdbc.datasource.SingleConnectionDataSource(source.getConnection(),true);
        var upgrade=new org.springframework.jdbc.core.JdbcTemplate(connection);
        try{
            upgrade.execute("set search_path to "+schema);var account=UUID.randomUUID();var site=UUID.randomUUID();
            upgrade.update("insert into payment_accounts values (?,'legacy','STRIPE','SANDBOX','ACTIVE','acct_legacy','stripe-sandbox-default',now(),0)",account);
            upgrade.update("insert into sites values (?,'legacy','Legacy',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site,account);
            upgrade.update("insert into payments(id,site_id,payment_account_id,source,external_order_id,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'DYNAMIC','legacy-order',2700,'EUR','NONE','CREATED',now(),now())",UUID.randomUUID(),site,account);
            upgrade.update("insert into provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_type,event_family,reference_object_type,provider_event_type,provider_api_version,occurred_at,received_at,payload_hash,safe_payload) values (?,'STRIPE',?,'evt_legacy','pi_legacy','PROVIDER_REFERENCE','PROVIDER_REFERENCE','PAYMENT','payment_intent.processing','2026-09-30.endive',now(),now(),?,'{}')",UUID.randomUUID(),account,"0".repeat(64));
            var before=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("payment_accounts","sites","payments","payment_events","outbox_events","provider_operations","provider_inbox","subscription_action_capabilities"))before.put(table,upgrade.queryForList("select * from "+table+" order by 1"));
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            for(var entry:before.entrySet())assertThat(upgrade.queryForList("select * from "+entry.getKey()+" order by 1")).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(upgrade.queryForList("select version from flyway_schema_history where success and version is not null order by installed_rank",String.class)).containsExactlyElementsOf(dev.genericpayments.platform.support.CurrentSchemaVersions.EXPECTED);
            upgrade.update("insert into provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_type,event_family,occurred_at,received_at,payload_hash) values (?,'STRIPE',?,'evt_schedule','sub_sched_owned','SUBSCRIPTION_SCHEDULE_CHANGED','SUBSCRIPTION',now(),now(),?)",UUID.randomUUID(),account,"1".repeat(64));
            assertThatThrownBy(()->upgrade.update("update provider_inbox set event_type='SUBSCRIPTION_OTHER' where provider_event_id='evt_schedule'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(()->upgrade.update("update provider_inbox set safe_payload='{\"raw\":true}' where provider_event_id='evt_legacy'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }finally{upgrade.execute("set search_path to public");upgrade.execute("drop schema "+schema+" cascade");connection.destroy();}
    }
}
