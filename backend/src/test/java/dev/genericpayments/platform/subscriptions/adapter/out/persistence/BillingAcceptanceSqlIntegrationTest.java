package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Hosted synthetic schema gate only. Never contacts Stripe or runs native acceptance. */
class BillingAcceptanceSqlIntegrationTest extends PostgresIntegrationTest {
    @Autowired DataSource source;

    @Test void exactOwnedAcceptanceSelectExecutesAgainstCurrentMigrationsReadOnly() throws Exception {
        var sql = Files.readString(Path.of("../infrastructure/stripe-billing-proof.sql"));
        assertThat(sql).contains("with owned as (").doesNotContain("select *", "delete from", "insert into", "update ");
        // Each parameter appears once in the scope CTE; preserve the production SQL verbatim otherwise.
        var jdbcSql = sql.replaceAll("\\$[0-9]+", "?");
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(jdbcSql)) {
                for (int i = 1; i <= 8; i++) statement.setObject(i, UUID.randomUUID());
                statement.setString(9, "a".repeat(64));
                statement.setString(10, "b".repeat(64));
                statement.setString(11, "http://127.0.0.1:18081");
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isFalse(); }
            } finally { connection.rollback(); }
        }
    }
}
