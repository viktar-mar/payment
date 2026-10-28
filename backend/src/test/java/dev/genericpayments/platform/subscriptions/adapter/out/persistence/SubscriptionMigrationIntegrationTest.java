package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class SubscriptionMigrationIntegrationTest extends PostgresIntegrationTest {
    @Autowired DataSource source;
    @Test void upgradesPopulatedV27PaymentGrantsWithoutChangingDescriptorsOrConsumedReceipts() throws Exception {
        String schema="billing_proof_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("27").load().migrate();
        var isolated=new SingleConnectionDataSource(source.getConnection(),true);var jdbc=new JdbcTemplate(isolated);
        try {
            jdbc.execute("set search_path to "+schema);
            UUID account=UUID.randomUUID(),site=UUID.randomUUID(),customer=UUID.randomUUID(),product=UUID.randomUUID(),price=UUID.randomUUID(),subscription=UUID.randomUUID(),operation=UUID.randomUUID(),step=UUID.randomUUID(),customerStep=UUID.randomUUID();
            jdbc.update("insert into payment_accounts values (?,'billing-upgrade','STRIPE','SANDBOX','ACTIVE','acct_owned','stripe-sandbox-default',now(),0)",account);
            jdbc.update("insert into sites values (?,'billing-upgrade','Billing upgrade',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site,account);
            jdbc.update("insert into customers(id,site_id,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())",customer,site);
            jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'upgrade-product','Upgrade','ACTIVE',now(),now())",product,site);
            jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,'upgrade-price',1,'SUBSCRIPTION',1000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',now(),now(),now())",price,site,product);
            jdbc.update("insert into subscriptions(id,site_id,customer_id,product_id,payment_account_id,currency,billing_price_id,billing_price_key,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,billing_trial_policy,billing_tax_mode,billing_status,provider_reference,created_at,updated_at) values (?,?,?,?,?,'EUR',?,'upgrade-price',1,1000,'MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','INCOMPLETE','sub_owned',now(),now())",subscription,site,customer,product,account,price);
            String key="subscription:"+account+":"+operation+":CREATE";
            jdbc.update("insert into subscription_operations(id,site_id,customer_id,subscription_id,kind,status,authority_scope,key_digest,fingerprint,provider_operation_key,expected_revision,policy_version,provider_reference,created_at,updated_at) values (?,?,?,?,'CREATE','PROCESSING','customer:upgrade','upgrade-key',?, ?,0,1,'sub_owned',now(),now())",operation,site,customer,subscription,"v1.paid."+"a".repeat(64),key);
            for(var row:List.of(new Object[]{customerStep,"CUSTOMER_CREATE",customer,"customer-upgrade","cus_owned"},new Object[]{step,"SUBSCRIPTION_CREATE",operation,key,"sub_owned"}))
                jdbc.update("insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,status,provider_reference,first_dispatched_at,retry_before) values (?,?,'acct_owned','stripe-sandbox-default',?,?,?,'original-fingerprint','ACKNOWLEDGED',?,now(),now()+interval '23 hours')",row[0],account,row[1],row[2],row[3],row[4]);
            jdbc.update("insert into stripe_billing_customers(id,payment_account_id,site_id,customer_id,provider_reference,creation_operation_id) values (?,?,?,?,?,?)",UUID.randomUUID(),account,site,customer,"cus_owned",customerStep);
            UUID grantId=UUID.randomUUID();
            jdbc.update("insert into subscription_action_capabilities(id,site_id,customer_id,payment_account_id,subscription_id,operation_id,purpose,token_digest,issued_at,expires_at,consumed_at,consent_purpose,recurring_consent,consent_policy_version,consent_at,billing_key_version,billing_step_id,billing_step_kind,billing_operation_key,billing_proof,billing_subscription_reference,billing_customer_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference) values (?,?,?,?,?,?,'PROVIDER_CLIENT',?,now(),now()+interval '10 minutes',now(),'CREATE',true,1,now(),'v1',?,'SUBSCRIPTION_CREATE',?,?,'sub_owned','cus_owned','in_owned','inpay_owned','pi_owned')",grantId,site,customer,account,subscription,operation,"b".repeat(64),step,key,"c".repeat(64));
            var before=jdbc.queryForMap("select * from subscription_action_capabilities where id=?",grantId);
            var legacy=dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.canonical(grantId,site,customer,account,subscription,operation,"v1",step,key,"c".repeat(64),"sub_owned","cus_owned","in_owned","inpay_owned","pi_owned",((java.sql.Timestamp)before.get("issued_at")).toInstant(),((java.sql.Timestamp)before.get("expires_at")).toInstant());
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("27.1").load().migrate();
            assertThat(jdbc.queryForMap("select * from subscription_action_capabilities where id=?",grantId)).isEqualTo(before);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            var after=jdbc.queryForMap("select * from subscription_action_capabilities where id=?",grantId);
            assertThat(after).containsAllEntriesOf(before).containsEntry("billing_schema","payment-v1").containsEntry("billing_step_reference","sub_owned").containsEntry("billing_setup_reference",null);
            var store=new SubscriptionActionPersistenceAdapter(jdbc,java.time.Clock.systemUTC());
            var restored=store.billing("b".repeat(64)).orElseThrow();assertThat(restored.consumedAt()).isNotNull();assertThat(restored.descriptor()).isEqualTo(legacy);
            assertThat(restored.binding().schema()).isEqualTo("payment-v1");assertThat(restored.binding().setupReference()).isNull();
            assertThatThrownBy(()->jdbc.update("update subscription_action_capabilities set consumed_at=null where id=?",grantId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(()->jdbc.update("update subscription_action_capabilities set billing_schema='resume-v1' where id=?",grantId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {jdbc.execute("set search_path to public");jdbc.execute("drop schema "+schema+" cascade");isolated.destroy();}
    }
    @Test void upgradesV12WithoutRewritingHistoricalAmountsVersionsOrScopes() throws Exception {
        String schema="subscription_migration_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("12").load().migrate();
        var isolated=new SingleConnectionDataSource(source.getConnection(),true);var jdbc=new JdbcTemplate(isolated);
        try {
            jdbc.execute("set search_path to "+schema);
            UUID account=UUID.randomUUID(),site=UUID.randomUUID(),product=UUID.randomUUID(),price=UUID.randomUUID(),customer=UUID.randomUUID();
            jdbc.update("insert into payment_accounts values (?,'legacy','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
            jdbc.update("insert into sites values (?,'legacy','Legacy',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site,account);
            jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'legacy-product','Legacy','ACTIVE',now(),now())",product,site);
            jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,tax_mode,status,created_at,updated_at,version) values (?,?,?,'legacy-price',4,'ONE_TIME',2700,'EUR','NONE','PUBLISHED',now(),now(),7)",price,site,product);
            jdbc.update("insert into customers(id,site_id,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())",customer,site);
            jdbc.update("insert into customer_sessions(id,site_id,customer_id,origin,token_digest,token_digest_key_version,scopes,issued_at,last_used_at,expires_at) values (?,?,?,'https://example.test','digest','v1','[\"CUSTOMER_READ\",\"CHECKOUT_CREATE\"]',now(),now(),now()+interval '1 hour')",UUID.randomUUID(),site,customer);
            jdbc.update("insert into payments(id,site_id,payment_account_id,source,external_order_id,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'DYNAMIC','legacy-order',2700,'EUR','NONE','CREATED',now(),now())",UUID.randomUUID(),site,account);
            var prices=jdbc.queryForList("select * from prices");var sessions=jdbc.queryForList("select * from customer_sessions");var payments=jdbc.queryForList("select * from payments");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForMap("select * from prices")).containsAllEntriesOf(prices.getFirst());
            assertThat(jdbc.queryForObject("select trial_payment_method_policy from prices",String.class)).isEqualTo("OPTIONAL_DURING_TRIAL");
            assertThat(jdbc.queryForList("select * from customer_sessions")).isEqualTo(sessions);
            var upgradedPayments=jdbc.queryForList("select * from payments");
            assertThat(upgradedPayments).hasSameSizeAs(payments);
            assertThat(upgradedPayments.getFirst()).containsAllEntriesOf(payments.getFirst());
            for (String column : List.of("subscription_id", "billing_record_id", "subscription_billing_kind",
                    "subscription_product_id", "subscription_price_id", "subscription_price_version",
                    "subscription_previous_price_id", "subscription_previous_price_version",
                    "subscription_period_start", "subscription_period_end", "subscription_invoice_reference")) {
                assertThat(upgradedPayments.getFirst()).containsEntry(column, null);
            }
            assertThat(jdbc.queryForObject("select count(*) from subscriptions",Integer.class)).isZero();
            assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema=? and table_name='subscription_billing_records'",String.class,schema)).contains("captured_policy_version","grace_deadline");
        } finally {jdbc.execute("set search_path to public");jdbc.execute("drop schema "+schema+" cascade");isolated.destroy();}
    }
}
