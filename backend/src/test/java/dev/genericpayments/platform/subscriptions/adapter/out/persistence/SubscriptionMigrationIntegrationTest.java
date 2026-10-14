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
