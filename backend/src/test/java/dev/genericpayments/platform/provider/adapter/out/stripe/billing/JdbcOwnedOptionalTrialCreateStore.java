package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.*;

/** Explicit construction only. Each statement commits independently before the caller proceeds. */
final class JdbcOwnedOptionalTrialCreateStore implements Store {
    private final JdbcTemplate jdbc;
    private final DataSource source;

    JdbcOwnedOptionalTrialCreateStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
        source = Objects.requireNonNull(jdbc.getDataSource());
    }

    public Authority admitted(Scope scope) {
        var rows=jdbc.query("""
            select c.* from stripe_billing_optional_trial_candidates c
            where c.run_id=? and c.clock_intent=? and c.subscription_id=? and c.create_operation=?
              and not exists(select 1 from stripe_billing_owned_optional_trial_creates r where r.clock_intent=c.clock_intent)
              and not exists(select 1 from provider_operations p where p.payment_account_id=c.account_id
                    and (p.resource_id=c.create_operation or p.operation_key=c.operation_key))
            """,(r,n)->new Authority(r.getObject("run_id",UUID.class),r.getObject("clock_intent",UUID.class),
                r.getObject("account_id",UUID.class),r.getObject("site_id",UUID.class),r.getObject("customer_id",UUID.class),
                r.getObject("subscription_id",UUID.class),r.getObject("create_operation",UUID.class),r.getObject("price_id",UUID.class),
                r.getObject("product_id",UUID.class),r.getString("price_key"),r.getString("external_account"),r.getString("credentials_ref"),
                r.getString("clock_reference"),r.getString("clock_name"),r.getString("customer_reference"),r.getString("price_reference"),
                r.getString("product_reference"),r.getString("operation_key"),r.getLong("frozen_time"),r.getLong("clock_created"),
                r.getLong("clock_deletes_after"),r.getLong("trial_end"),r.getString("fingerprint")),
            scope.run(),scope.clockIntent(),scope.subscription(),scope.createOperation());
        return rows.size()==1 ? rows.getFirst() : null;
    }

    public boolean claim(Authority a, UUID claim) {
        return committed("select claim_owned_optional_trial(?,?,?,?,?,?)",
            a.run(), a.clockIntent(), a.subscription(), a.createOperation(), a.fingerprint(), claim);
    }

    public boolean dispatchAuthorized(Authority a, UUID claim) {
        long beforeRead = System.nanoTime();
        Long remainingMillis = jdbc.queryForObject("""
            select coalesce(max(floor(extract(epoch from (p.lease_expires_at-clock_timestamp()))*1000)),0)::bigint
            from stripe_billing_optional_trial_candidates c
            join stripe_billing_owned_optional_trial_creates r on r.clock_intent=c.clock_intent
              and r.create_operation=c.create_operation and r.subscription_id=c.subscription_id
              and r.fingerprint=c.fingerprint and r.trial_end=c.trial_end
            join provider_operations p on p.id=r.step_id and p.resource_id=c.create_operation
              and p.payment_account_id=c.account_id and p.kind='SUBSCRIPTION_CREATE'
              and p.fingerprint=c.fingerprint and p.operation_key=c.operation_key
              and p.external_account_id=c.external_account and p.credentials_ref=c.credentials_ref
            where c.run_id=? and c.clock_intent=? and c.subscription_id=? and c.create_operation=? and c.fingerprint=?
              and r.claim_id=? and p.lease_owner=r.claim_id::text and p.first_dispatched_at=r.claimed_at
              and r.status='DISPATCHING' and p.status='DISPATCHING'
            """, Long.class, a.run(), a.clockIntent(), a.subscription(), a.createOperation(), a.fingerprint(), claim);
        // Charge the whole read round trip against the database's remaining lease.
        return remainingMillis != null && TimeUnit.MILLISECONDS.toNanos(remainingMillis) > System.nanoTime() - beforeRead;
    }

    public boolean finish(UUID claim, Created created) {
        return committed("select finish_owned_optional_trial(?,?,?,?,?::bigint)", claim,
            created == null ? null : created.subscription(), created == null ? null : created.item(),
            created == null ? null : created.invoice(), created == null ? null : created.trialStart());
    }

    private boolean committed(String sql, Object... parameters) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("Optional trial ownership requires an independent transaction");
        }
        try (var connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                boolean accepted;
                try (var statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
                    try (var result = statement.executeQuery()) {
                        if (!result.next()) throw new SQLException("Missing optional trial ownership decision");
                        accepted = result.getBoolean(1);
                    }
                }
                connection.commit();
                return accepted;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Optional trial ownership commit could not be confirmed", failure);
        }
    }
}
