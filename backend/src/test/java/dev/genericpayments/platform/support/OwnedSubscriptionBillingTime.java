package dev.genericpayments.platform.support;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Hosted fixture only. FactService already owns the Account lock; stop requires that lock.
 * One nonlocking statement participates in the caller's transaction. Never call the independent
 * clock stores here, acquire another lock, or register this implementation in production. */
public final class OwnedSubscriptionBillingTime implements SubscriptionBillingTimePort {
    private final JdbcTemplate jdbc;
    public Object sampledConnectionHolder;
    public String sampledTransaction;
    public Sample lastSample;
    public OwnedSubscriptionBillingTime(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Sample sample(PaymentAccountId account, SiteId site, CustomerId customer, Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");
        sampledConnectionHolder = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        // Start at admission, not the successful inner join: missing READY/receipt must never mean ordinary.
        // Site/Customer scope also detects a caller supplying a foreign Account for an admitted owner.
        var rows = jdbc.query("""
            select pg_current_xact_id()::text as transaction_id, r.frozen_time,
              (c.payment_account_id=? and c.stopped_at is null and r.state='READY'
               and a.account_status='ACTIVE' and a.provider='STRIPE' and a.account_mode='SANDBOX'
               and a.external_account_id=c.external_account_id and a.credentials_ref=c.credentials_ref
               and s.status='ACTIVE' and s.payment_account_id=c.payment_account_id and u.status='ACTIVE'
               and k.status='ACKNOWLEDGED' and k.operation_key=c.create_key and k.fingerprint=c.create_fingerprint
               and k.provider_reference=r.reference and k.external_account_id=c.external_account_id and k.credentials_ref=c.credentials_ref
               and b.provider_reference=i.customer_reference and b.creation_operation_id=i.customer_creation_operation
               and cp.status='ACKNOWLEDGED' and cp.fingerprint=r.customer_fingerprint
               and cp.operation_key='billing_customer_'||c.payment_account_id::text||'_'||c.site_id::text||'_'||c.customer_id::text
               and cp.provider_reference=b.provider_reference and cp.external_account_id=c.external_account_id and cp.credentials_ref=c.credentials_ref
               and p.status='ACKNOWLEDGED' and p.operation_key=i.operation_key and p.fingerprint=i.fingerprint
               and p.provider_reference=e.reference and p.external_account_id=c.external_account_id and p.credentials_ref=c.credentials_ref
               and e.period_start<=r.frozen_time and r.frozen_time<e.period_end
               and l.provider_reference=e.reference and o.provider_reference=e.reference) as valid
            from stripe_billing_clock_intents c
            left join stripe_billing_clock_ready r on r.intent_id=c.id
            left join stripe_billing_owned_subscriptions i on i.clock_intent=c.id
            left join stripe_billing_owned_subscription_receipts e on e.intent_id=i.id
            left join provider_operations k on k.id=r.creation_operation_id
            left join stripe_billing_customers b on b.id=i.customer_binding
            left join provider_operations cp on cp.id=i.customer_creation_operation
            left join provider_operations p on p.id=e.provider_operation
            left join subscriptions l on l.id=i.subscription_id
            left join subscription_operations o on o.id=i.create_operation
            left join payment_accounts a on a.id=c.payment_account_id
            left join sites s on s.id=c.site_id
            left join customers u on u.id=c.customer_id and u.site_id=c.site_id
            where c.site_id=? and c.customer_id=?
            """, (r,n) -> {
                sampledTransaction = r.getString("transaction_id");
                if (!r.getBoolean("valid")) throw new IllegalStateException("OWNED_TIME_AUTHORITY_MISSING");
                return new Sample(Instant.ofEpochSecond(r.getLong("frozen_time")), Mode.ISOLATED);
            }, account.value(), site.value(), customer.value());
        if (rows.size()>1) throw new IllegalStateException("OWNED_TIME_AUTHORITY_AMBIGUOUS");
        lastSample = rows.isEmpty() ? new Sample(at, Mode.ORDINARY) : rows.getFirst();
        return lastSample;
    }

    @Override public void requireUnscopedAllowed(SiteId site, CustomerId customer) {
        String sql = "select count(*) from stripe_billing_clock_intents where 1=1";
        var args = new java.util.ArrayList<Object>();
        if (site != null) { sql += " and site_id=?"; args.add(site.value()); }
        if (customer != null) { sql += " and customer_id=?"; args.add(customer.value()); }
        if (jdbc.queryForObject(sql, Long.class, args.toArray()) != 0)
            throw new IllegalStateException("OWNED_TIME_UNSCOPED");
    }
}
