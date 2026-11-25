package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import javax.sql.DataSource;

/** Verifies the production-shaped SELECT against real committed Cucumber receipts and isolated bad rows. */
final class StripeBillingRequiredTrialSqlAssertions {
    private StripeBillingRequiredTrialSqlAssertions() {}

    static void verify(DataSource source, List<Object> owned, UUID bill, UUID event, long revision) throws Exception {
        var sql=Files.readString(Path.of("../infrastructure/stripe-billing-required-trial-proof.sql"));
        var placeholders=Pattern.compile("\\$[0-9]+");
        assertThat(placeholders.matcher(sql).results().map(MatchResult::group).toList())
            .containsExactlyElementsOf(IntStream.rangeClosed(1,17).mapToObj(position -> "$"+position).toList());
        assertThat(owned).hasSize(17);
        var query=placeholders.matcher(sql).replaceAll("?");
        try (var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (var statement=select(connection,query,owned); var rows=statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("run_id")).isEqualTo(owned.getFirst());
                assertThat(rows.getObject("billing_record_id")).isEqualTo(bill);
                assertThat(rows.getObject("trial_started_event_id")).isEqualTo(event);
                assertThat(rows.getLong("revision")).isEqualTo(revision);
                assertThat(rows.getTimestamp("trial_start")).isEqualTo(owned.get(15));
                assertThat(rows.getTimestamp("trial_end")).isEqualTo(owned.get(16));
                var observed=rows.getTimestamp("access_evaluated_at").toInstant();
                assertThat(rows.getTimestamp("access_valid_until").toInstant())
                    .isEqualTo(dev.genericpayments.platform.subscriptions.domain.SubscriptionAccessPolicy.validUntil(observed,((Timestamp)owned.get(16)).toInstant()));
                assertThat(rows.next()).isFalse();
            }
            for (int i=0;i<owned.size();i++) {
                var substitution=new ArrayList<>(owned);
                var value=owned.get(i);
                substitution.set(i,switch(value) {
                    case UUID id -> UUID.randomUUID();
                    case Timestamp time -> Timestamp.from(time.toInstant().plusSeconds(1));
                    default -> value.equals(owned.get(8)) ? "f".repeat(64) : value+"foreign";
                });
                refuses(connection,query,substitution,"substituted parameter "+(i+1));
            }
            connection.rollback();
        }

        // The schema-shaped shadows keep the original application receipts intact.
        // Each temporary mutation runs with the proof in a read-only transaction, then rolls back.
        var changedMethod=new ArrayList<>(owned);
        changedMethod.set(11,"pm_foreign");
        refusesChangedReceipt(source,query,changedMethod,"stripe_subscription_setup_proofs",
            "update pg_temp.stripe_subscription_setup_proofs set method_reference='pm_foreign' where operation_id=?",owned.get(7));
        var siblingInvoice=new ArrayList<>(owned);
        siblingInvoice.set(14,"in_trial");
        refusesChangedReceipt(source,query,siblingInvoice,"stripe_subscription_trial_proofs",
            "update pg_temp.stripe_subscription_trial_proofs set invoice_reference='in_trial' where operation_id=?",owned.get(7));
        // Keep matching references in the shadows to isolate the required terminal statuses.
        refusesChangedReceipt(source,query,owned,"subscription_operations",
            "update pg_temp.subscription_operations set status='UNKNOWN' where id=? and kind='CREATE'",owned.get(7));
        refusesChangedReceipt(source,query,owned,"provider_operations",
            "update pg_temp.provider_operations set status='UNKNOWN' where resource_id=? and kind='SETUP_INTENT_CREATE'",owned.get(7));
        refusesChangedReceipt(source,query,owned,"provider_operations",
            "update pg_temp.provider_operations set status='UNKNOWN' where resource_id=? and kind='SUBSCRIPTION_CREATE'",owned.get(7));
        refusesChangedReceipt(source,query,owned,"provider_operations",
            "update pg_temp.provider_operations set fingerprint=repeat('f',64) where resource_id=? and kind='CUSTOMER_CREATE'",owned.get(3));
        refusesChangedReceipt(source,query,owned,"subscription_trial_eligibility",
            "update pg_temp.subscription_trial_eligibility set consumed_at=null where reserved_by_operation=?",owned.get(7));
        refusesChangedReceipt(source,query,owned,"subscription_events",
            "update pg_temp.subscription_events set fact_key='projection:foreign' where id=?",event);
        refusesChangedReceipt(source,query,owned,"payments","insert into pg_temp.payments(site_id) values (?)",owned.get(2));
        refusesChangedReceipt(source,query,owned,"payment_events","insert into pg_temp.payment_events(site_id) values (?)",owned.get(2));
    }

    private static void refusesChangedReceipt(DataSource source,String query,List<Object> owned,String table,String mutation,Object parameter) throws Exception {
        try (var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try (var setup=connection.createStatement()) {
                setup.execute("create temporary table "+table+" as table public."+table);
            }
            connection.commit();
            connection.setReadOnly(true);
            try {
                try (var changed=connection.prepareStatement(mutation)) {
                    changed.setObject(1,parameter);
                    assertThat(changed.executeUpdate()).isOne();
                }
                refuses(connection,query,owned,"changed "+table);
            } finally {
                connection.rollback();
                connection.setReadOnly(false);
                try (var cleanup=connection.createStatement()) { cleanup.execute("drop table pg_temp."+table); }
                connection.commit();
            }
        }
    }

    private static void refuses(Connection connection,String query,List<Object> parameters,String reason) throws Exception {
        try (var statement=select(connection,query,parameters); var rows=statement.executeQuery()) {
            assertThat(rows.next()).as(reason).isFalse();
        }
    }

    private static PreparedStatement select(Connection connection,String query,List<Object> parameters) throws Exception {
        var statement=connection.prepareStatement(query);
        statement.setQueryTimeout(5);
        for (int i=0;i<parameters.size();i++) statement.setObject(i+1,parameters.get(i));
        return statement;
    }
}
