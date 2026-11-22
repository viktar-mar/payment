package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.sql.Connection;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                statement.setBoolean(12, false);
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isFalse(); }
            } finally { connection.rollback(); }
        }
    }

    @Test void cancellationProofReadsTypedScheduledAndWithdrawnRowsWithOriginalConfirmation() throws Exception {
        var sql = Files.readString(Path.of("../infrastructure/stripe-billing-cancellation-proof.sql"));
        var fixture = Files.readString(Path.of("../infrastructure/fixtures/stripe-billing-cancellation-proof-rows.sql"));
        try (var connection = source.getConnection(); var setup = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                setup.execute(fixture);
                var initialSql = Files.readString(Path.of("../infrastructure/stripe-billing-proof.sql"));
                try (var initial = connection.prepareStatement(initialSql.replaceAll("\\$[0-9]+", "?"))) {
                    bindInitial(initial);
                    initial.setBoolean(12, false);
                    try (var first = initial.executeQuery()) {
                        assertThat(first.next()).isTrue();
                        var json = new ObjectMapper();
                        assertThat(first.getLong("subscription_revision")).isEqualTo(3L);
                        assertThat(first.getBoolean("initial_unscheduled")).isTrue();
                        assertThat(json.readTree(first.getString("callbacks")).size()).isEqualTo(3);
                        var flags = json.readTree(first.getString("proof"));
                        for (var flag : java.util.List.of("cash", "journal", "original", "webhook"))
                            assertThat(flags.get(flag).asBoolean()).as(flag).isTrue();
                        assertThat(json.readTree(first.getString("cash_journal_ids")).get(0).asText()).isEqualTo(fixtureId(12).toString());
                        assertThat(json.readTree(first.getString("paid_journal_ids")).get(0).asText()).isEqualTo(fixtureId(13).toString());
                        assertThat(first.next()).isFalse();
                    }
                }
                setup.execute("call pg_temp.record_cancel_command(false)");
                assertCancellationRow(connection, sql, false, true, true);
                setup.execute("call pg_temp.record_cancel_command(true)");
                assertCancellationRow(connection, sql, true, true, true);
                setup.execute("update pg_temp.subscription_confirmation_attempts set confirm_key=repeat('b',64)");
                assertCancellationRow(connection, sql, true, false, true);
                setup.execute("insert into pg_temp.subscription_operations(id,site_id,customer_id,subscription_id,kind) " +
                    "values(pg_temp.cid(90),pg_temp.cid(2),pg_temp.cid(3),pg_temp.cid(4),'RECONCILE')");
                assertCancellationRow(connection, sql, true, false, false);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test void retryProofRequiresCausalDeclineNoCashAndOnePaidSuccessor() throws Exception {
        var dir = Path.of("../infrastructure");
        var query = Files.readString(dir.resolve("stripe-billing-initial-retry-proof.sql"));
        try (var connection = source.getConnection(); var setup = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                setup.execute(Files.readString(dir.resolve("fixtures/stripe-billing-cancellation-proof-rows.sql")));
                setup.execute(Files.readString(dir.resolve("fixtures/stripe-billing-initial-retry-proof-rows.sql")));
                assertRetryRow(connection, query, true, true, true, true);
                var paid = connection.setSavepoint();
                setup.execute("update pg_temp.subscription_confirmation_attempts set predecessor=null where id=pg_temp.cid(51)");
                assertRetryRow(connection, query, true, false, true, true);
                connection.rollback(paid);
                setup.execute("insert into pg_temp.payments(site_id,customer_id) values(pg_temp.cid(2),pg_temp.cid(80))");
                assertRetryRow(connection, query, true, true, false, true);
                connection.rollback(paid);
                setup.execute("call pg_temp.only_original_decline()");
                assertRetryRow(connection, query, false, true, true, false);
                setup.execute("insert into pg_temp.payment_events(site_id) values(pg_temp.cid(2))");
                assertRetryRow(connection, query, false, true, false, false);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertRetryRow(Connection connection, String query, boolean recovered, boolean lineage,
                                       boolean phaseProof, boolean journal) throws Exception {
        try (var select = connection.prepareStatement(query.replaceAll("\\$[0-9]+", "?"))) {
            bindInitial(select);
            select.setString(12, "s5a-" + fixtureId(99).toString().replace("-", ""));
            select.setObject(13, recovered ? fixtureId(51) : null);
            select.setString(14, recovered ? "3".repeat(64) : null);
            select.setString(15, recovered ? "4".repeat(64) : null);
            select.setQueryTimeout(5);
            try (var row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean("original")).isTrue();
                assertThat(row.getBoolean("lineage")).isEqualTo(lineage);
                assertThat(row.getBoolean("phase_proof")).isEqualTo(phaseProof);
                assertThat(row.getBoolean("journal")).isEqualTo(journal);
                assertThat(row.next()).isFalse();
            }
        }
    }

    private static void assertCancellationRow(Connection c, String sql, boolean withdrawn, boolean original, boolean closed)
        throws Exception {
        var json = new ObjectMapper();
        try (var select = c.prepareStatement(sql.replaceAll("\\$[0-9]+", "?"))) {
            bindInitial(select);
            select.setString(12, "s5a-" + fixtureId(99).toString().replace("-", ""));
            select.setObject(13, fixtureId(20));
            select.setString(14, "3".repeat(64));
            select.setObject(15, withdrawn ? fixtureId(21) : null);
            select.setString(16, withdrawn ? "4".repeat(64) : null);
            select.setQueryTimeout(5);
            try (var row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean("cash")).isTrue();
                assertThat(row.getBoolean("journal")).isTrue();
                assertThat(row.getBoolean("original")).isEqualTo(original);
                assertThat(row.getBoolean("closed_inventory")).isEqualTo(closed);
                assertThat(row.getObject("original_paid_event_id")).isEqualTo(fixtureId(13));
                assertThat(row.getObject("cancellation_at")).isEqualTo(withdrawn ? null : row.getObject("period_end"));
                var receipts = json.readTree(row.getString("receipts"));
                assertThat(receipts.size()).isEqualTo(withdrawn ? 2 : 1);
                receipts.forEach(r -> assertThat(r.get("acknowledged").asBoolean()).isTrue());
                assertThat(receipts.get(0).get("operation_id").asText()).isEqualTo(fixtureId(20).toString());
                if (withdrawn) {
                    assertThat(receipts.get(1).get("operation_id").asText()).isEqualTo(fixtureId(21).toString());
                    assertThat(receipts.get(1).get("prior_event").asText()).isEqualTo(receipts.get(0).get("event_id").asText());
                    assertThat(receipts.get(1).get("prior_event_revision")).isEqualTo(receipts.get(0).get("revision"));
                }
                var callbacks = json.readTree(row.getString("callbacks"));
                assertThat(callbacks.size()).isEqualTo(withdrawn ? 5 : 4);
                callbacks.forEach(e -> assertThat(e.get("delivered").asBoolean()).isTrue());
                assertThat(row.next()).isFalse();
            }
        }
    }

    @Test void challengeAndOrdinaryProofRequireTheirOwnOriginalAttemptState() throws Exception {
        var query = Files.readString(Path.of("../infrastructure/stripe-billing-proof.sql")).replaceAll("\\$[0-9]+", "?");
        var fixture = Files.readString(Path.of("../infrastructure/fixtures/stripe-billing-cancellation-proof-rows.sql"));
        try (var connection = source.getConnection(); var setup = connection.createStatement();
             var select = connection.prepareStatement(query)) {
            connection.setAutoCommit(false);
            try {
                setup.execute(fixture);
                bindInitial(select);
                for (var attemptState : java.util.List.of("OBSERVE", "REQUIRES_ACTION")) {
                    try (var update = connection.prepareStatement("update pg_temp.subscription_confirmation_attempts set state=? where id=?")) {
                        update.setString(1, attemptState);
                        update.setObject(2, fixtureId(6));
                        assertThat(update.executeUpdate()).isOne();
                    }
                    for (boolean challenge : new boolean[]{false, true}) {
                        select.setBoolean(12, challenge);
                        try (var row = select.executeQuery()) {
                            assertThat(row.next()).isTrue();
                            var proof = new ObjectMapper().readTree(row.getString("proof"));
                            assertThat(proof.path("original").asBoolean()).isEqualTo(challenge == attemptState.equals("REQUIRES_ACTION"));
                            assertThat(proof.path("cash").asBoolean()).isTrue();
                            assertThat(proof.path("journal").asBoolean()).isTrue();
                            assertThat(row.next()).isFalse();
                        }
                    }
                }
            } finally { connection.rollback(); }
        }
    }

    private static void bindInitial(java.sql.PreparedStatement select) throws java.sql.SQLException {
        for (int i = 1; i <= 8; i++) select.setObject(i, fixtureId(i));
        select.setString(9, "1".repeat(64));
        select.setString(10, "2".repeat(64));
        select.setString(11, "http://127.0.0.1:18081");
    }

    private static UUID fixtureId(int number) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(number));
    }
}
