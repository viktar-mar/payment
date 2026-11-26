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
final class StripeBillingTrialSqlAssertions {
    private StripeBillingTrialSqlAssertions() {}

    static void verifyOptionalResume(DataSource source, List<Object> parameters, UUID payment, UUID cashEvent, UUID periodEvent)
            throws Exception {
        var sql=Files.readString(Path.of("../infrastructure/stripe-billing-optional-trial-resume-proof.sql"));
        var placeholders=Pattern.compile("\\$[0-9]+");
        assertThat(placeholders.matcher(sql).results().map(MatchResult::group).toList())
            .containsExactlyElementsOf(IntStream.rangeClosed(1,23).mapToObj(position -> "$"+position).toList());
        assertThat(parameters).hasSize(23);
        var query=placeholders.matcher(sql).replaceAll("?");
        try (var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (var statement=select(connection,query,parameters); var rows=statement.executeQuery()) {
                assertThat(rows.next()).as("owned optional trial resumption").isTrue();
                assertThat(rows.getObject("subscription_id")).isEqualTo(parameters.get(6));
                assertThat(rows.getObject("trial_bill_id")).isEqualTo(parameters.get(8));
                assertThat(rows.getObject("resume_bill_id")).isEqualTo(parameters.get(20));
                assertThat(rows.getObject("payment_id")).isEqualTo(payment);
                assertThat(rows.getObject("cash_event_id")).isEqualTo(cashEvent);
                assertThat(rows.getObject("period_event_id")).isEqualTo(periodEvent);
                assertThat(rows.next()).isFalse();
            }
            for (int i=0;i<parameters.size();i++) {
                var substituted=new ArrayList<>(parameters);
                var value=parameters.get(i);
                substituted.set(i,switch(value) {
                    case null -> "pi_foreign";
                    case UUID id -> UUID.randomUUID();
                    case Timestamp time -> Timestamp.from(time.toInstant().plusSeconds(1));
                    default -> value+"foreign";
                });
                refuses(connection,query,substituted,"substituted resume parameter "+(i+1));
            }
            connection.rollback();
        }
        refusesChangedReceipt(source,query,parameters,"stripe_subscription_resume_proofs",
            "update pg_temp.stripe_subscription_resume_proofs set invoice_reference='in_foreign' where operation_id=?",parameters.get(16));
        refusesChangedReceipt(source,query,parameters,"stripe_subscription_setup_proofs",
            "update pg_temp.stripe_subscription_setup_proofs set method_reference=null where operation_id=?",parameters.get(15));
        refusesChangedReceipt(source,query,parameters,"subscription_operations",
            "update pg_temp.subscription_operations set updated_at=updated_at+interval '1 day' where id=?",parameters.get(15));
        refusesChangedReceipt(source,query,parameters,"provider_operations",
            "update pg_temp.provider_operations set status='DISPATCHING' where resource_id=? and kind='SETUP_INTENT_CREATE'",parameters.get(15));
        refusesChangedReceipt(source,query,parameters,"provider_operations",
            "update pg_temp.provider_operations set status='DISPATCHING' where resource_id=? and kind='SUBSCRIPTION_UPDATE'",parameters.get(16));
        refusesChangedReceipt(source,query,parameters,"subscription_billing_records",
            "update pg_temp.subscription_billing_records set amount_due_minor=1 where id=?",parameters.get(8));
        refusesChangedReceipt(source,query,parameters,"subscription_provider_objects",
            "delete from pg_temp.subscription_provider_objects where billing_record_id=? and object_type='INVOICE'",parameters.get(8));
        refusesChangedReceipt(source,query,parameters,"subscription_provider_objects",
            """
            insert into pg_temp.subscription_provider_objects
                (id,site_id,subscription_id,payment_account_id,provider_type,object_type,provider_reference,billing_record_id)
            select gen_random_uuid(),site_id,subscription_id,payment_account_id,'STRIPE','INVOICE','in_foreign',billing_record_id
            from public.subscription_provider_objects where billing_record_id=? and object_type='INVOICE'
            """,parameters.get(20));
        if (payment==null) {
            refusesChangedReceipt(source,query,parameters,"subscriptions",
                "update pg_temp.subscriptions set paid_through=trial_end where id=?",parameters.get(6));
            refusesChangedReceipt(source,query,parameters,"payments",
                "insert into pg_temp.payments(site_id) values (?)",parameters.getFirst());
        } else {
            refusesChangedReceipt(source,query,parameters,"payments",
                "update pg_temp.payments set subscription_invoice_reference='in_foreign' where id=?",payment);
            refusesChangedReceipt(source,query,parameters,"payments",
                "update pg_temp.payments set amount_minor=1900 where id=?",payment);
            refusesChangedReceipt(source,query,parameters,"payments",
                "update pg_temp.payments set refunded_amount_minor=100 where id=?",payment);
            refusesChangedReceipt(source,query,parameters,"payment_events",
                "update pg_temp.payment_events set amount_minor=1900 where id=?",cashEvent);
            refusesChangedReceipt(source,query,parameters,"subscription_events",
                "update pg_temp.subscription_events set billing_record_id=null where id=?",periodEvent);
            refusesChangedReceipt(source,query,parameters,"subscription_provider_objects",
                "update pg_temp.subscription_provider_objects set payment_id=null where payment_id=? and object_type='PAYMENT'",payment);
            refusesChangedReceipt(source,query,parameters,"outbox_events",
                "delete from pg_temp.outbox_events where id=?",cashEvent);
        }
    }

    static void verifyOptionalPause(DataSource source, List<Object> owned, UUID pauseEvent, long revision) throws Exception {
        var sql=Files.readString(Path.of("../infrastructure/stripe-billing-optional-trial-pause-proof.sql"));
        var placeholders=Pattern.compile("\\$[0-9]+");
        assertThat(placeholders.matcher(sql).results().map(MatchResult::group).toList())
            .containsExactlyElementsOf(IntStream.rangeClosed(1,14).mapToObj(position -> "$"+position).toList());
        assertThat(owned).hasSize(14);
        var query=placeholders.matcher(sql).replaceAll("?");
        try (var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (var statement=select(connection,query,owned); var rows=statement.executeQuery()) {
                assertThat(rows.next()).as("owned optional trial pause").isTrue();
                assertThat(rows.getObject("subscription_id")).isEqualTo(owned.get(6));
                assertThat(rows.getObject("billing_record_id")).isEqualTo(owned.get(8));
                assertThat(rows.getObject("trial_started_event_id")).isEqualTo(owned.get(9));
                assertThat(rows.getObject("paused_event_id")).isEqualTo(pauseEvent);
                assertThat(rows.getLong("revision")).isEqualTo(revision);
                assertThat(rows.getTimestamp("trial_end")).isEqualTo(owned.get(13));
                assertThat(rows.next()).isFalse();
            }
            for (int i=0;i<owned.size();i++) {
                var substituted=new ArrayList<>(owned);
                var value=owned.get(i);
                substituted.set(i,switch(value) {
                    case UUID id -> UUID.randomUUID();
                    case Timestamp time -> Timestamp.from(time.toInstant().plusSeconds(1));
                    default -> value+"foreign";
                });
                refuses(connection,query,substituted,"substituted pause parameter "+(i+1));
            }
            connection.rollback();
        }
        refusesChangedReceipt(source,query,owned,"subscriptions",
            "update pg_temp.subscriptions set billing_status='TRIALING' where id=?",owned.get(6));
        refusesChangedReceipt(source,query,owned,"subscriptions",
            "update pg_temp.subscriptions set debt_billing_record_id=id where id=?",owned.get(6));
        refusesChangedReceipt(source,query,owned,"subscriptions",
            "update pg_temp.subscriptions set paid_through=trial_end where id=?",owned.get(6));
        refusesChangedReceipt(source,query,owned,"subscriptions",
            "update pg_temp.subscriptions set billing_trial_policy='REQUIRED_BEFORE_TRIAL' where id=?",owned.get(6));
        refusesChangedReceipt(source,query,owned,"subscription_billing_records",
            "update pg_temp.subscription_billing_records set provider_invoice_reference='in_foreign' where id=?",owned.get(8));
        refusesChangedReceipt(source,query,owned,"subscription_billing_records",
            """
            insert into pg_temp.subscription_billing_records (id,site_id,subscription_id,payment_account_id)
            select gen_random_uuid(),site_id,subscription_id,payment_account_id
            from public.subscription_billing_records where id=?
            """,owned.get(8));
        refusesChangedReceipt(source,query,owned,"subscription_events",
            "update pg_temp.subscription_events set occurred_at=occurred_at-interval '1 second' where id=?",pauseEvent);
        refusesChangedReceipt(source,query,owned,"subscription_provider_objects",
            "update pg_temp.subscription_provider_objects set provider_reference='in_foreign' where billing_record_id=? and object_type='INVOICE'",owned.get(8));
        refusesChangedReceipt(source,query,owned,"subscription_provider_objects",
            """
            insert into pg_temp.subscription_provider_objects
                (id,site_id,subscription_id,payment_account_id,provider_type,object_type,provider_reference,billing_record_id)
            select gen_random_uuid(),site_id,subscription_id,payment_account_id,'STRIPE','INVOICE','in_foreign',billing_record_id
            from public.subscription_provider_objects where billing_record_id=? and object_type='INVOICE'
            """,owned.get(8));
        refusesChangedReceipt(source,query,owned,"provider_operations",
            "update pg_temp.provider_operations set status='DISPATCHING' where resource_id=? and kind='SUBSCRIPTION_CREATE'",owned.get(7));
        refusesChangedReceipt(source,query,owned,"stripe_subscription_setup_proofs",
            """
            insert into pg_temp.stripe_subscription_setup_proofs (site_id,subscription_id,payment_account_id)
            select site_id,subscription_id,payment_account_id from stripe_subscription_trial_proofs where operation_id=?
            """,owned.get(7));
        refusesChangedReceipt(source,query,owned,"payments","insert into pg_temp.payments(site_id) values (?)",owned.getFirst());
        refusesChangedReceipt(source,query,owned,"payment_events","insert into pg_temp.payment_events(site_id) values (?)",owned.getFirst());
    }

    static void verify(DataSource source, List<Object> owned, UUID bill, UUID event, long revision, String providerPrice, String providerProduct) throws Exception {
        verifySeed(source,owned,providerPrice,providerProduct);
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

    private static void verifySeed(DataSource source, List<Object> owned, String providerPrice, String providerProduct) throws Exception {
        var sql=Files.readString(Path.of("../infrastructure/stripe-billing-required-trial-seed.sql"));
        var placeholders=Pattern.compile("\\$[0-9]+");
        assertThat(placeholders.matcher(sql).results().map(MatchResult::group).toList())
            .containsExactlyElementsOf(IntStream.rangeClosed(1,9).mapToObj(position -> "$"+position).toList());
        var query=placeholders.matcher(sql).replaceAll("?");
        var parameters=owned.subList(0,9);
        var columns=List.of("customer_reference","setup_reference","method_reference","subscription_reference",
            "item_reference","invoice_reference","trial_start","trial_end","provider_price_id","provider_product_id");
        var references=new ArrayList<>(owned.subList(9,17));
        references.add(providerPrice);
        references.add(providerProduct);
        try (var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (var statement=select(connection,query,parameters); var rows=statement.executeQuery()) {
                var metadata=rows.getMetaData();
                assertThat(metadata.getColumnCount()).isEqualTo(columns.size());
                assertThat(rows.next()).as("exact owned seed").isTrue();
                for (int i=0;i<columns.size();i++) {
                    assertThat(metadata.getColumnLabel(i+1)).isEqualTo(columns.get(i));
                    assertThat(rows.getObject(i+1)).as(columns.get(i)).isEqualTo(references.get(i));
                }
                assertThat(rows.next()).isFalse();
            }
            for (int i=0;i<parameters.size();i++) {
                var substitution=new ArrayList<>(parameters);
                substitution.set(i,i==8?"f".repeat(64):UUID.randomUUID());
                refuses(connection,query,substitution,"substituted seed parameter "+(i+1));
            }
            connection.rollback();
        }
        refusesChangedReceipt(source,query,parameters,"payment_accounts",
            "update pg_temp.payment_accounts set external_account_id='acct_other' where id=?",owned.get(1));
        refusesChangedReceipt(source,query,parameters,"payment_accounts",
            "update pg_temp.payment_accounts set account_mode='LIVE' where id=?",owned.get(1));
        refusesChangedReceipt(source,query,parameters,"sites",
            "update pg_temp.sites set site_key='s5a-sibling' where id=?",owned.get(2));
        refusesChangedReceipt(source,query,parameters,"prices",
            "update pg_temp.prices set status='DRAFT' where id=?",owned.get(5));
        refusesChangedReceipt(source,query,parameters,"prices",
            "update pg_temp.prices set trial_payment_method_policy='OPTIONAL_DURING_TRIAL' where id=?",owned.get(5));
        for (var alteredTerms:List.of("trial_days=3", "amount_minor=1999", "currency='USD'", "billing_interval='YEAR'", "tax_mode='NONE'")) {
            refusesChangedReceipt(source,query,parameters,"prices",
                "update pg_temp.prices set "+alteredTerms+" where id=?",owned.get(5));
        }
        refusesChangedReceipt(source,query,parameters,"provider_price_mappings",
            "update pg_temp.provider_price_mappings set provider_price_id='price_foreign' where price_id=?",owned.get(5));
        refusesChangedReceipt(source,query,parameters,"provider_product_mappings",
            "update pg_temp.provider_product_mappings set provider_product_id='prod_foreign' where product_id=?",owned.get(4));
        refusesChangedReceipt(source,query,parameters,"provider_operations",
            "update pg_temp.provider_operations set status='DISPATCHING' where resource_id=? and kind='CUSTOMER_CREATE'",owned.get(3));
        refusesChangedReceipt(source,query,parameters,"provider_operations",
            "update pg_temp.provider_operations set status='DISPATCHING' where resource_id=? and kind='SETUP_INTENT_CREATE'",owned.get(7));
        refusesChangedReceipt(source,query,parameters,"provider_operations",
            "update pg_temp.provider_operations set status='DISPATCHING' where resource_id=? and kind='SUBSCRIPTION_CREATE'",owned.get(7));
        refusesChangedReceipt(source,query,parameters,"provider_operations",
            "update pg_temp.provider_operations set recovery_attempts=1 where resource_id=? and kind='SETUP_INTENT_CREATE'",owned.get(7));
        refusesChangedReceipt(source,query,parameters,"provider_operations",
            "update pg_temp.provider_operations set first_dispatched_at=first_dispatched_at-interval '1 day' where resource_id=? and kind='CUSTOMER_CREATE'",owned.get(3));
        refusesChangedReceipt(source,query,parameters,"stripe_subscription_setup_proofs",
            "update pg_temp.stripe_subscription_setup_proofs set method_reference=null where operation_id=?",owned.get(7));
        refusesChangedReceipt(source,query,parameters,"stripe_subscription_setup_proofs",
            "update pg_temp.stripe_subscription_setup_proofs set method_reference='pm_foreign' where operation_id=?",owned.get(7));
        refusesChangedReceipt(source,query,parameters,"stripe_subscription_setup_proofs",
            "update pg_temp.stripe_subscription_setup_proofs set setup_reference='seti_foreign' where operation_id=?",owned.get(7));
        refusesChangedReceipt(source,query,parameters,"stripe_subscription_trial_proofs",
            "update pg_temp.stripe_subscription_trial_proofs set invoice_reference='in_trial' where operation_id=?",owned.get(7));
        refusesChangedReceipt(source,query,parameters,"stripe_subscription_trial_proofs",
            "update pg_temp.stripe_subscription_trial_proofs set trial_start=trial_start-1 where operation_id=?",owned.get(7));
        refusesChangedReceipt(source,query,parameters,"subscription_trial_eligibility",
            "update pg_temp.subscription_trial_eligibility set consumed_at=null where reserved_by_operation=?",owned.get(7));
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
                // Keep the shadow query out of the stable proof's named-statement cache.
                try (var statement=select(connection,"/* temp shadow assertion */\n"+query,owned)) {
                    statement.unwrap(org.postgresql.PGStatement.class).setPrepareThreshold(0);
                    try (var rows=statement.executeQuery()) {
                        assertThat(rows.next()).as("changed "+table).isFalse();
                    }
                }
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
