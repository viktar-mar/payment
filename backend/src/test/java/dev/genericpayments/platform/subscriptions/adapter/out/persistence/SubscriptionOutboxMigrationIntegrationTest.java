package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class SubscriptionOutboxMigrationIntegrationTest extends PostgresIntegrationTest {
    @Autowired DataSource source;

    @Test void populatedV17OutboxRetainsLegacyPayloadAndHistoricalAccount() throws Exception {
        var schema="outbox_migration_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("17").load().migrate();
        var isolated=new SingleConnectionDataSource(source.getConnection(),true);
        var jdbc=new JdbcTemplate(isolated);
        try {
            jdbc.execute("set search_path to "+schema);
            var account=UUID.randomUUID();var replacement=UUID.randomUUID();var site=UUID.randomUUID();
            var payment=UUID.randomUUID();var event=UUID.randomUUID();var secret=UUID.randomUUID();
            for(var id:java.util.List.of(account,replacement))jdbc.update("insert into payment_accounts values (?,?,'FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",id,"migration-"+id);
            jdbc.update("insert into sites values (?,'legacy-outbox','Legacy',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site,replacement);
            jdbc.update("insert into payments(id,site_id,payment_account_id,source,external_order_id,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'DYNAMIC','legacy-order',2700,'EUR','NONE','SUCCEEDED',now(),now())",payment,site,account);
            jdbc.update("insert into payment_events(id,site_id,payment_id,payment_account_id,sequence_number,event_type,source,occurred_at,received_at,request_id,payload_hash) values (?,?,?,?,1,'PAYMENT_SUCCEEDED','PROVIDER_EVENT',now(),now(),'migration',repeat('a',64))",event,site,payment,account);
            jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at) values (?,?,1,decode('abcd','hex'),decode(repeat('00',12),'hex'),'test-fixture',now())",secret,site);
            jdbc.update("insert into outbox_events(id,site_id,payment_id,callback_type,payload,endpoint_url,secret_version_id,next_attempt_at,created_at,updated_at) values (?,?,?,'payment.succeeded','{\"schema_version\":1,\"legacy\":\"unchanged\"}'::jsonb,'https://callback.example.test',?,now(),now(),now())",event,site,payment,secret);
            var old=jdbc.queryForMap("select * from outbox_events");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForMap("select * from outbox_events")).containsAllEntriesOf(old)
                .containsEntry("payment_account_id",account).containsEntry("source_kind","PAYMENT")
                .containsEntry("subscription_id",null).containsEntry("subscription_event_id",null);
            assertThat(jdbc.queryForObject("select payment_account_id from sites where id=?",UUID.class,site)).isEqualTo(replacement);
            assertThatThrownBy(()->jdbc.update("update outbox_events set payment_account_id=? where id=?",replacement,event))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("set search_path to public");jdbc.execute("drop schema "+schema+" cascade");isolated.destroy();
        }
    }
}
