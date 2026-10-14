package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class SubscriptionPaymentLinkMigrationIntegrationTest extends SubscriptionCashFixture {
    @Autowired DataSource source;
    @Test void populatedV19UpgradeLinksEveryActualObjectWithoutChangingPaymentsOrJournal() throws Exception {
        var failed=record("upgrade-decline",PaymentStatus.FAILED,"decline");
        var paid=record("upgrade-paid",PaymentStatus.SUCCEEDED,"paid");
        String schema="subscription_link_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("19").load().migrate();
        var connection=new SingleConnectionDataSource(source.getConnection(),true);var upgrade=new JdbcTemplate(connection);
        try {
            // Copy only public test-created rows, into a test-owned schema at the accepted version.
            for(String table:List.of("payment_accounts","sites","customers","products","prices","subscriptions","subscription_billing_records","payments","payment_events")) {
                String columns=String.join(",",upgrade.queryForList("select column_name from information_schema.columns where table_schema=? and table_name=? and is_generated='NEVER' order by ordinal_position",String.class,schema,table));
                upgrade.execute("insert into "+schema+"."+table+" ("+columns+") select "+columns+" from public."+table);
            }
            upgrade.execute("set search_path to "+schema);
            var beforePayments=upgrade.queryForList("select * from payments order by id");
            var beforeEvents=upgrade.queryForList("select * from payment_events order by id");
            assertThat(upgrade.queryForObject("select count(*) from subscription_provider_objects",Integer.class)).isZero();
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(upgrade.queryForList("select payment_id from subscription_provider_objects where object_type='PAYMENT'",UUID.class)).containsExactlyInAnyOrder(failed.paymentId().value(),paid.paymentId().value());
            assertThat(upgrade.queryForObject("select count(*) from subscription_provider_objects where site_id=? and subscription_id=? and payment_account_id=? and billing_record_id=?",Integer.class,site.value(),subscription.id(),account.id().value(),bill.id())).isEqualTo(2);
            assertThat(upgrade.queryForList("select * from payments order by id")).isEqualTo(beforePayments);
            assertThat(upgrade.queryForList("select * from payment_events order by id")).isEqualTo(beforeEvents);
            assertThatThrownBy(()->upgrade.update("update subscription_provider_objects set provider_reference='rewritten' where payment_id=?",paid.paymentId().value())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            upgrade.execute("set search_path to public");upgrade.execute("drop schema "+schema+" cascade");connection.destroy();
        }
    }
}
