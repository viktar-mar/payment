package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Hosted synthetic schema gate; no local SQL, listener, or provider execution. */
class BillingFirstPaymentSqlIntegrationTest extends PostgresIntegrationTest {
    @Autowired DataSource source;
    @Test void exactOwnedInitialSelectExecutesAgainstMigrationsReadOnly() throws Exception {
        var sql=Files.readString(Path.of("../infrastructure/stripe-billing-first-payment-proof.sql"));
        assertThat(sql).contains("with owned as (", "owned-initial-v1", "stripe_billing_owned_subscription_receipts")
            .doesNotContain("provider_inbox", "select *", "delete from", "insert into", "update ");
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);connection.setReadOnly(true);
            try(var statement=connection.prepareStatement(sql.replaceAll("\\$[0-9]+", "?"))) {
                for(int i=1;i<=8;i++)statement.setObject(i,UUID.randomUUID());
                statement.setString(9,"a".repeat(64));statement.setString(10,"b".repeat(64));
                statement.setString(11,"http://127.0.0.1:18081");statement.setObject(12,UUID.randomUUID());statement.setLong(13,1790000000L);
                statement.setQueryTimeout(5);try(var rows=statement.executeQuery()){assertThat(rows.next()).isFalse();}
            }finally{connection.rollback();}
        }
    }
}
