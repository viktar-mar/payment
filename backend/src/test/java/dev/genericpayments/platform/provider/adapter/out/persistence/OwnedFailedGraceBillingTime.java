package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.adapter.out.persistence.OwnedGraceSubscriptionRows;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.support.OwnedFailedRenewalBillingTime;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit hosted test support only. A committed original READY can supply exact-read time;
 * historical writer/unscoped behavior still belongs to the existing concrete selector chain. */
public final class OwnedFailedGraceBillingTime implements SubscriptionBillingTimePort {
    private static final String CALLER="OWNED_TIME_REQUIRES_CALLER_TRANSACTION";
    private static final String MISSING="OWNED_TIME_AUTHORITY_MISSING";
    private static final String AMBIGUOUS="OWNED_TIME_AUTHORITY_AMBIGUOUS";
    private final JdbcTemplate jdbc;
    private final EntityManagerFactory factory;
    private final OwnedFailedRenewalBillingTime original;
    private final OwnedGraceSubscriptionRows subscriptions;
    public Object sampledConnectionHolder;
    public String sampledTransaction;

    public OwnedFailedGraceBillingTime(JdbcTemplate jdbc,EntityManagerFactory factory,OwnedFailedRenewalBillingTime original){
        this.jdbc=Objects.requireNonNull(jdbc);this.factory=Objects.requireNonNull(factory);this.original=Objects.requireNonNull(original);
        subscriptions=new OwnedGraceSubscriptionRows(jdbc);
    }
    @Override public Sample sample(PaymentAccountId account,SiteId site,CustomerId customer,Instant operationalAt){
        sampledConnectionHolder=null;sampledTransaction=null;
        var value=original.sample(account,site,customer,operationalAt);
        sampledConnectionHolder=original.sampledConnectionHolder;sampledTransaction=original.sampledTransaction;return value;
    }
    @Override public void requireUnscopedAllowed(SiteId site,CustomerId customer){original.requireUnscopedAllowed(site,customer);}

    @Override public Sample sampleForRead(Subscription supplied,Instant operationalAt){
        sampledConnectionHolder=null;sampledTransaction=null;
        var caller=OwnedGraceCallerConnection.capture(jdbc,factory,CALLER);requireSameReadCommitted(caller);
        sampledConnectionHolder=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        if(supplied==null||operationalAt==null)throw new IllegalStateException(MISSING);
        List<Candidate> candidates;
        try{
            candidates=jdbc.query(READ_CANDIDATES,this::candidate,supplied.paymentAccountId().value(),
                supplied.siteId().value(),supplied.customerId().value(),supplied.id());
        }catch(RuntimeException failure){requireSameReadCommitted(caller);throw new IllegalStateException(MISSING);}
        requireSameReadCommitted(caller);
        if(candidates.size()>1)throw new IllegalStateException(AMBIGUOUS);
        if(candidates.isEmpty()){
            // Only absence under BOTH owner and actual-ID discovery arms permits the concrete old chain.
            Sample value;
            try{value=original.sample(supplied.paymentAccountId(),supplied.siteId(),supplied.customerId(),operationalAt);}
            catch(RuntimeException failure){
                requireSameReadCommitted(caller);var diagnostic=failure.getMessage();
                throw new IllegalStateException(CALLER.equals(diagnostic)?CALLER:AMBIGUOUS.equals(diagnostic)?AMBIGUOUS:MISSING);
            }
            requireSameReadCommitted(caller);sampledTransaction=value.isolated()?original.sampledTransaction:null;return value;
        }
        var candidate=candidates.getFirst();
        if(!candidate.valid()||candidate.frozenTime()==null||candidate.transaction()==null||!supplied.equals(candidate.current()))throw new IllegalStateException(MISSING);
        Sample value;
        try{value=new Sample(Instant.ofEpochSecond(candidate.frozenTime()),Mode.ISOLATED);}
        catch(RuntimeException malformed){throw new IllegalStateException(MISSING);}
        requireSameReadCommitted(caller);sampledTransaction=candidate.transaction();return value;
    }
    private void requireSameReadCommitted(OwnedGraceCallerConnection caller){
        caller.requireSame();
        try{
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            if(DataSourceUtils.getTargetConnection(holder.getConnection()).getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new IllegalStateException(CALLER);
        }catch(SQLException|RuntimeException failure){throw new IllegalStateException(CALLER);}
    }
    private record Candidate(boolean valid,Long frozenTime,String transaction,Subscription current){}
    private Candidate candidate(ResultSet row,int index){
        try{
            boolean valid=row.getBoolean("owned_valid");valid=valid&&!row.wasNull();
            long epoch=row.getLong("owned_frozen_time");Long frozen=row.wasNull()?null:epoch;
            var transaction=row.getString("owned_transaction_id");
            var current=row.getObject("id",UUID.class)==null?null:subscriptions.map(row,index);
            return new Candidate(valid,frozen,transaction,current);
        }catch(SQLException|RuntimeException malformed){return new Candidate(false,null,null,null);}
    }

    /** One nonlocking statement retains invalid/foreign siblings and independently discovers all
     * V39 evidence. Presence permanently closes the historical branch; NULL validity is refusal.
     * Stored producer chronology is checked against its original budgets, never current expiry. */
    private static final String READ_CANDIDATES="""
        with caller as materialized(select pg_current_xact_id() as transaction_id,clock_timestamp() as utc)
        select s.*,caller.transaction_id::text as owned_transaction_id,
         case when o.id is null and q.preparation_id is null and ack.preparation_id is null and ready.id is null and cleanup.preparation_id is null
          then predecessor.frozen_time else ready.frozen_time end as owned_frozen_time,
         (d.payment_account_id=? and a.id is not null and predecessor.id is not null
          and d.admitted_at>=predecessor.observed_at and owned_failed_renewal_authority(d.id)
          and case when o.id is null and q.preparation_id is null and ack.preparation_id is null and ready.id is null and cleanup.preparation_id is null
           then p.id is null or owned_failed_grace_identity(p.id)
           else p.id is not null and o.id is not null and q.preparation_id is not null
            and ack.preparation_id is not null and ready.id is not null and cleanup.preparation_id is null
            and owned_failed_grace_owns(p.id,o)
            -- Reader-only projection of V38 current eligibility: the writer's VOLATILE
            -- function deliberately takes a fresh snapshot and cannot be called here.
            -- Keep these joins/predicates aligned with V38; only its DBUTC reference differs.
            and exists(select 1 from stripe_billing_failed_renewals current_d
             join stripe_billing_first_renewals current_a on current_a.id=current_d.first_renewal_admission
             join stripe_billing_owned_subscriptions current_i on current_i.id=current_a.owned_intent
             join stripe_billing_first_renewal_ready current_r on current_r.id=current_d.ready_id and current_r.admission_id=current_a.id
             join stripe_billing_first_renewal_steps current_step on current_step.id=current_r.step_id and current_step.admission_id=current_a.id
             join stripe_billing_first_renewal_boundary current_b on current_b.id=current_d.boundary_id and current_b.admission_id=current_a.id
             join stripe_billing_failed_renewal_failures current_f on current_f.admission_id=current_d.id
             join stripe_billing_failed_renewal_reads current_q on current_q.id=current_f.claim_id and current_q.admission_id=current_d.id and current_q.phase='FAILED'
             where current_d.id=d.id and current_d.source_sha=current_a.source_sha
              and owned_failed_renewal_authority(current_d.id) and owned_failed_renewal_failure_current(current_d.id,null)
              and current_step.ordinal=2 and current_step.boundary_id=current_b.id and current_step.predecessor_ready=current_b.ready_id
              and current_step.target=current_r.frozen_time and current_d.admitted_at>=current_r.observed_at
              and current_f.recorded_at>=current_d.admitted_at and current_f.recorded_at>=current_r.observed_at and current_f.recorded_at<=caller.utc
              and current_f.projected_revision=current_a.subscription_revision+1 and current_f.observation_sequence=current_q.observation_sequence+1
              and current_f.renewal_bill<>current_a.initial_bill and current_f.invoice_reference=current_b.invoice_reference and current_f.invoice_reference<>current_a.invoice_reference
              and current_f.allocation_reference<>current_a.allocation_reference and current_f.payment_intent_reference<>current_a.payment_intent_reference
              and row(current_f.period_start,current_f.period_end)=row(current_b.period_start,current_b.period_end)
              and current_f.grace_deadline=date_trunc('second',current_f.grace_deadline) and current_f.period_end=date_trunc('second',current_f.period_end)
              and extract(epoch from current_f.grace_deadline)-1>current_step.target::numeric
              and current_f.grace_deadline+interval '1 second'<current_f.period_end
              and extract(epoch from current_f.grace_deadline)+1-current_step.target::numeric<=case current_i.billing_interval when 'MONTH' then 56::numeric*86400 when 'YEAR' then 730::numeric*86400 else -1 end
              and not exists(select 1 from stripe_billing_failed_method_reservations x where x.admission_id=current_d.id)
              and not exists(select 1 from stripe_billing_failed_renewal_recoveries x where x.admission_id=current_d.id)
              and not exists(select 1 from stripe_billing_failed_renewal_publications x where x.admission_id=current_d.id)
              and not exists(select 1 from stripe_billing_failed_renewal_stops x where x.admission_id=current_d.id)
              and not exists(select 1 from subscription_operations x where x.subscription_id=current_d.subscription_id
               and x.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and x.kind<>'RECONCILE'))
            and p.admission_id=d.id and p.predecessor_ready=predecessor.id
            and p.prepared_at>=f.recorded_at and p.prepared_at>=predecessor.observed_at
            and p.prepared_at<=q.claimed_at and p.prepared_at<=caller.utc
            and row(q.preparation_id,q.operation_id)=row(p.id,o.id)
            and row(ack.preparation_id,ack.operation_id,ack.invocation,ack.claimant,ack.deadline)
             =row(q.preparation_id,q.operation_id,q.invocation,q.claimant,q.deadline)
            and row(ready.preparation_id,ready.admission_id,ready.operation_id,ready.invocation)
             =row(p.id,d.id,q.operation_id,q.invocation)
            and q.recorded_transaction<>ack.recorded_transaction and q.recorded_transaction<>ready.recorded_transaction
            and ack.recorded_transaction<>ready.recorded_transaction
            and q.recorded_transaction<>caller.transaction_id and ack.recorded_transaction<>caller.transaction_id
            and ready.recorded_transaction<>caller.transaction_id
            and o.status='ACKNOWLEDGED' and o.version=2 and o.provider_reference=predecessor.reference
            and o.first_dispatched_at=q.claimed_at and o.retry_before=q.claimed_at+interval '23 hours'
            and o.lease_owner is null and o.lease_expires_at is null and o.safe_error_code is null
            and o.recovery_attempts=0 and not o.manual_review_required
            and q.deadline_at=q.entered_at+interval '30 seconds' and q.deadline=q.claimed_at+interval '60 seconds'
            and q.entered_at<=q.claimed_at and q.claimed_at<q.deadline_at and q.claimed_at<=caller.utc
            and ack.observed_at>=q.claimed_at and ack.acknowledged_at>=ack.observed_at
            and ack.acknowledged_at<q.deadline_at and ack.acknowledged_at<q.deadline and ack.acknowledged_at<=caller.utc
            and ready.observed_at>=ack.acknowledged_at and ready.observed_at<=caller.utc
            and ready.observed_at<q.deadline_at and ready.observed_at<q.deadline
            and row(ack.reference,ack.object_type,ack.livemode,ack.name,ack.provider_created,ack.provider_deletes_after)
             =row(predecessor.reference,predecessor.object_type,predecessor.livemode,predecessor.name,predecessor.provider_created,predecessor.provider_deletes_after)
            and row(ready.reference,ready.object_type,ready.livemode,ready.name,ready.provider_created,ready.provider_deletes_after)
             =row(ack.reference,ack.object_type,ack.livemode,ack.name,ack.provider_created,ack.provider_deletes_after)
            and ack.object_type='test_helpers.test_clock' and not ack.livemode
            and (ack.advancing_target is null or ack.advancing_target=p.target)
            and ((ack.status='ready' and ack.frozen_time=p.target)
             or (ack.status='advancing' and ack.frozen_time>=predecessor.frozen_time and ack.frozen_time<=p.target))
            and ready.status='ready' and ready.frozen_time=p.target
            and (ready.advancing_target is null or ready.advancing_target=p.target)
           end) as owned_valid
        from stripe_billing_failed_renewals d
        cross join caller
        left join subscriptions s on s.id=d.subscription_id
        left join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
         and a.payment_account_id=d.payment_account_id and a.site_id=d.site_id and a.customer_id=d.customer_id
         and a.subscription_id=d.subscription_id and a.source_sha=d.source_sha
        left join stripe_billing_first_renewal_ready predecessor on predecessor.id=d.ready_id and predecessor.admission_id=d.first_renewal_admission
        left join stripe_billing_failed_renewal_failures f on f.admission_id=d.id
        left join stripe_billing_failed_grace_preparations p on p.admission_id=d.id
        left join provider_operations o on o.kind='OWNED_GRACE_CLOCK_ADVANCE' and o.resource_id=p.id
        left join stripe_billing_failed_grace_claims q on q.preparation_id=p.id
        left join stripe_billing_failed_grace_acks ack on ack.preparation_id=p.id
        left join stripe_billing_failed_grace_ready ready on ready.preparation_id=p.id or ready.admission_id=d.id
        left join stripe_billing_failed_grace_cleanup cleanup on cleanup.preparation_id=p.id or cleanup.operation_id=o.id
        where (d.site_id=? and d.customer_id=?) or d.subscription_id=?
        """;
}
