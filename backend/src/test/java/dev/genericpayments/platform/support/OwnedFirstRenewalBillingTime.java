package dev.genericpayments.platform.support;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Hosted-only, nonlocking selector. A prepared renewal can never disappear into ordinary time. */
public final class OwnedFirstRenewalBillingTime implements SubscriptionBillingTimePort {
    private final JdbcTemplate jdbc;
    private final OwnedSubscriptionBillingTime original;
    public Object sampledConnectionHolder;
    public String sampledTransaction;

    public OwnedFirstRenewalBillingTime(JdbcTemplate jdbc,OwnedSubscriptionBillingTime original) {
        this.jdbc=jdbc;this.original=original;
    }

    @Override public Sample sample(PaymentAccountId account,SiteId site,CustomerId customer,Instant utcAt) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");
        sampledConnectionHolder=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        var rows=jdbc.query("""
            select pg_current_xact_id()::text as transaction_id,chain.target,
             (a.payment_account_id=? and a.stopped_at is null and chain.ready_id is not null
              and (owned_first_renewal_original(a.id) or owned_first_renewal_committed(a.id))) as valid
            from stripe_billing_first_renewals a
            left join lateral (select id from stripe_billing_first_renewal_steps where admission_id=a.id order by ordinal desc limit 1) highest on true
            left join owned_first_renewal_chain(a.id) chain on chain.step_id=highest.id
            where a.site_id=? and a.customer_id=?
            """,(r,n)->{
                sampledTransaction=r.getString("transaction_id");
                if(!r.getBoolean("valid"))throw new IllegalStateException("OWNED_TIME_AUTHORITY_MISSING");
                return new Sample(Instant.ofEpochSecond(r.getLong("target")),Mode.ISOLATED);
            },account.value(),site.value(),customer.value());
        if(rows.size()>1)throw new IllegalStateException("OWNED_TIME_AUTHORITY_AMBIGUOUS");
        if(!rows.isEmpty())return rows.getFirst();
        var sample=original.sample(account,site,customer,utcAt);sampledTransaction=original.sampledTransaction;return sample;
    }

    @Override public void requireUnscopedAllowed(SiteId site,CustomerId customer) {
        var sql="select count(*) from stripe_billing_first_renewals where 1=1";
        var args=new java.util.ArrayList<Object>();
        if(site!=null){sql+=" and site_id=?";args.add(site.value());}
        if(customer!=null){sql+=" and customer_id=?";args.add(customer.value());}
        if(jdbc.queryForObject(sql,Long.class,args.toArray())!=0)throw new IllegalStateException("OWNED_TIME_UNSCOPED");
        original.requireUnscopedAllowed(site,customer);
    }
}
