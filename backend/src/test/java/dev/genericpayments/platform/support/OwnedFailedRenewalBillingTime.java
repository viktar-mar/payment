package dev.genericpayments.platform.support;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit hosted-fixture selector. Original READY2 supplies time; existing services retain authority. */
public final class OwnedFailedRenewalBillingTime implements SubscriptionBillingTimePort {
    private final JdbcTemplate jdbc;
    private final OwnedFirstRenewalBillingTime original;
    public Object sampledConnectionHolder;
    public String sampledTransaction;

    public OwnedFailedRenewalBillingTime(JdbcTemplate jdbc,OwnedFirstRenewalBillingTime original) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.original=Objects.requireNonNull(original);
    }

    private record Candidate(boolean valid,long frozenTime) {}

    @Override public Sample sample(PaymentAccountId account,SiteId site,CustomerId customer,Instant operationalAt) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");
        sampledConnectionHolder=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        sampledTransaction=null;
        // Keep every failed candidate visible, including stopped or damaged evidence. The full
        // predecessor identity and exact original READY2 must authenticate before time is returned.
        var rows=jdbc.query("""
            select pg_current_xact_id()::text as transaction_id,r.frozen_time,
             (d.payment_account_id=? and a.id is not null and r.id is not null
              and d.admitted_at>=r.observed_at and owned_failed_renewal_authority(d.id)) as valid
            from stripe_billing_failed_renewals d
            left join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
             and a.payment_account_id=d.payment_account_id and a.site_id=d.site_id
             and a.customer_id=d.customer_id and a.subscription_id=d.subscription_id and a.source_sha=d.source_sha
            left join stripe_billing_first_renewal_ready r on r.id=d.ready_id and r.admission_id=d.first_renewal_admission
            where d.site_id=? and d.customer_id=?
            """,(r,n)->{
                sampledTransaction=r.getString("transaction_id");
                return new Candidate(r.getBoolean("valid"),r.getLong("frozen_time"));
            },account.value(),site.value(),customer.value());
        if(rows.size()>1)throw new IllegalStateException("OWNED_TIME_AUTHORITY_AMBIGUOUS");
        if(!rows.isEmpty()) {
            var candidate=rows.getFirst();
            if(!candidate.valid())throw new IllegalStateException("OWNED_TIME_AUTHORITY_MISSING");
            return new Sample(Instant.ofEpochSecond(candidate.frozenTime()),Mode.ISOLATED);
        }
        var sample=original.sample(account,site,customer,operationalAt);
        sampledTransaction=sample.isolated()?original.sampledTransaction:null;
        return sample;
    }

    @Override public void requireUnscopedAllowed(SiteId site,CustomerId customer) {
        var sql="select count(*) from stripe_billing_failed_renewals where 1=1";
        var args=new ArrayList<Object>();
        if(site!=null){sql+=" and site_id=?";args.add(site.value());}
        if(customer!=null){sql+=" and customer_id=?";args.add(customer.value());}
        if(jdbc.queryForObject(sql,Long.class,args.toArray())!=0)
            throw new IllegalStateException("OWNED_TIME_UNSCOPED");
        original.requireUnscopedAllowed(site,customer);
    }
}
