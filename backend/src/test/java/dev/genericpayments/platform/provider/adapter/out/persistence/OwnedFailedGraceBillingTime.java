package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.adapter.out.persistence.OwnedGraceSubscriptionRows;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.support.OwnedFailedRenewalBillingTime;
import dev.genericpayments.platform.support.OwnedFirstRenewalBillingTime;
import dev.genericpayments.platform.support.OwnedSubscriptionBillingTime;
import jakarta.persistence.EntityManagerFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Hosted exact reads from original grace receipts and their committed recovered successor. */
public final class OwnedFailedGraceBillingTime implements SubscriptionBillingTimePort {
    private static final String CALLER="OWNED_TIME_REQUIRES_CALLER_TRANSACTION";
    private static final String MISSING="OWNED_TIME_AUTHORITY_MISSING";
    private static final String AMBIGUOUS="OWNED_TIME_AUTHORITY_AMBIGUOUS";
    private final JdbcTemplate jdbc;
    private final EntityManagerFactory factory;
    private final OwnedFailedRenewalBillingTime original;
    private final OwnedFirstRenewalBillingTime readFallback;
    private final OwnedGraceSubscriptionRows subscriptions;
    public Object sampledConnectionHolder;
    public String sampledTransaction;

    public OwnedFailedGraceBillingTime(JdbcTemplate jdbc,EntityManagerFactory factory,OwnedFailedRenewalBillingTime original){
        this.jdbc=Objects.requireNonNull(jdbc);this.factory=Objects.requireNonNull(factory);this.original=Objects.requireNonNull(original);
        subscriptions=new OwnedGraceSubscriptionRows(jdbc);
        // Every read-fallback tier must use the authenticated template. The independently
        // injected chain remains solely the historical writer/unscoped implementation.
        readFallback=new OwnedFirstRenewalBillingTime(jdbc,new OwnedSubscriptionBillingTime(jdbc));
    }
    @Override public Sample sample(PaymentAccountId account,SiteId site,CustomerId customer,Instant operationalAt){
        sampledConnectionHolder=null;sampledTransaction=null;
        var value=original.sample(account,site,customer,operationalAt);
        sampledConnectionHolder=original.sampledConnectionHolder;sampledTransaction=original.sampledTransaction;return value;
    }
    @Override public void requireUnscopedAllowed(SiteId site,CustomerId customer){original.requireUnscopedAllowed(site,customer);}

    @Override public Sample sampleForRead(Subscription supplied,Instant operationalAt){
        sampledConnectionHolder=null;sampledTransaction=null;
        var caller=OwnedGraceCallerConnection.capture(jdbc,factory,CALLER);caller.requireReadCommitted();
        sampledConnectionHolder=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        if(supplied==null||operationalAt==null)throw new IllegalStateException(MISSING);
        List<Candidate> candidates;
        try{
            candidates=jdbc.query(READ_CANDIDATES,this::candidate,supplied.paymentAccountId().value(),
                supplied.siteId().value(),supplied.customerId().value(),supplied.id());
        }catch(RuntimeException failure){caller.requireReadCommitted();throw new IllegalStateException(MISSING);}
        caller.requireReadCommitted();
        if(candidates.size()>1)throw new IllegalStateException(AMBIGUOUS);
        if(candidates.isEmpty()){
            // Neither discovery arm found a failed admission. Do not rediscover one in a later snapshot.
            Sample value;
            try{value=readFallback.sample(supplied.paymentAccountId(),supplied.siteId(),supplied.customerId(),operationalAt);}
            catch(RuntimeException failure){
                caller.requireReadCommitted();var diagnostic=failure.getMessage();
                throw new IllegalStateException(CALLER.equals(diagnostic)?CALLER:AMBIGUOUS.equals(diagnostic)?AMBIGUOUS:MISSING);
            }
            caller.requireReadCommitted();sampledTransaction=value.isolated()?readFallback.sampledTransaction:null;return value;
        }
        var candidate=candidates.getFirst();
        if(!candidate.valid()||candidate.frozenTime()==null||candidate.transaction()==null||!supplied.equals(candidate.current()))throw new IllegalStateException(MISSING);
        Sample value;
        try{value=new Sample(Instant.ofEpochSecond(candidate.frozenTime()),Mode.ISOLATED);}
        catch(RuntimeException malformed){throw new IllegalStateException(MISSING);}
        caller.requireReadCommitted();sampledTransaction=candidate.transaction();return value;
    }
    private record Candidate(boolean valid,Long frozenTime,String transaction,Subscription current){}
    private Candidate candidate(ResultSet row,int index){
        try{
            boolean valid=row.getBoolean("owned_valid");valid=valid&&!row.wasNull();
            boolean successor=row.getBoolean("owned_successor_present");
            if(successor)valid=valid&&row.getBoolean("owned_successor_projected")&&!row.wasNull();
            long epoch=row.getLong(successor?"owned_after_frozen_time":"owned_frozen_time");Long frozen=row.wasNull()?null:epoch;
            var transaction=row.getString("owned_transaction_id");
            var current=row.getObject("id",UUID.class)==null?null:subscriptions.map(row,index);
            return new Candidate(valid,frozen,transaction,current);
        }catch(SQLException|RuntimeException malformed){return new Candidate(false,null,null,null);}
    }

    /** One nonlocking RC statement classifies all failed-admission evidence. Each phase
     * presence check is independent of its READY and of the other phase's receipts. */
    private static final String READ_CANDIDATES="""
        with caller as materialized(select pg_current_xact_id() as transaction_id,clock_timestamp() as utc),
        candidates as materialized(
         select d.id,d.payment_account_id=? as account_matches,d.subscription_id,p.id as preparation,
          owned_grace_successor_present(d.id) as successor,method.id as reservation,method.after_ready_id,
          a.id is not null and predecessor.id is not null and d.admitted_at>=predecessor.observed_at as rooted,
          predecessor.frozen_time as ready2,ready.frozen_time as last_second,
          case when orphan.after_present or owned_grace_boundary_present(p.id,3) then 3
               when orphan.at_present or owned_grace_boundary_present(p.id,2) then 2
               when o.id is not null or q.preparation_id is not null or ack.preparation_id is not null
                 or ready.id is not null or cleanup.preparation_id is not null then 1
               else 0 end as phase
         from stripe_billing_failed_renewals d
         left join stripe_billing_first_renewals a on a.id=d.first_renewal_admission
          and a.payment_account_id=d.payment_account_id and a.site_id=d.site_id and a.customer_id=d.customer_id
          and a.subscription_id=d.subscription_id and a.source_sha=d.source_sha
         left join stripe_billing_first_renewal_ready predecessor on predecessor.id=d.ready_id and predecessor.admission_id=d.first_renewal_admission
         left join stripe_billing_failed_grace_preparations p on p.admission_id=d.id
         left join stripe_billing_failed_method_reservations method on method.admission_id=d.id and method.after_ready_id is not null
         -- Without a local preparation, receipts and exact operation keys still identify
         -- conflicts by admission. These locators can only cause refusal.
         left join lateral (
          select bool_or(e.phase=2) as at_present,bool_or(e.phase=3) as after_present from (
           select phase from stripe_billing_failed_grace_boundary_claims where admission_id=d.id
           union all select phase from stripe_billing_failed_grace_boundary_ready where admission_id=d.id
           union all select 2 from provider_operations where operation_key='billing-failed-grace-boundary-v1:'||d.id::text||':at'
           union all select 3 from provider_operations where operation_key='billing-failed-grace-boundary-v1:'||d.id::text||':after'
          ) e
         ) orphan on p.id is null
         left join provider_operations o on o.kind='OWNED_GRACE_CLOCK_ADVANCE' and o.resource_id=p.id
         left join stripe_billing_failed_grace_claims q on q.preparation_id=p.id
         left join stripe_billing_failed_grace_acks ack on ack.preparation_id=p.id
         left join stripe_billing_failed_grace_ready ready on ready.preparation_id=p.id or ready.admission_id=d.id
         left join stripe_billing_failed_grace_cleanup cleanup on cleanup.preparation_id=p.id or cleanup.operation_id=o.id
         where (d.site_id=? and d.customer_id=?) or d.subscription_id=?
        )
        select s.*,caller.transaction_id::text as owned_transaction_id,
         c.successor as owned_successor_present,after_ready.frozen_time as owned_after_frozen_time,
         case when c.successor and after_ready.id is not null then
          owned_grace_successor_projected(c.reservation,caller.utc,caller.transaction_id) else false end as owned_successor_projected,
         case c.phase when 0 then c.ready2 when 1 then c.last_second else boundary.frozen_time end as owned_frozen_time,
         (c.account_matches and c.rooted and owned_failed_renewal_authority(c.id)
          and case when c.successor then true else case c.phase when 0 then c.preparation is null or owned_failed_grace_identity(c.preparation)
              when 1 then owned_grace_original_committed(c.preparation,caller.utc,caller.transaction_id)
              else owned_grace_phase_committed(c.preparation,c.phase,caller.utc,caller.transaction_id) end end) as owned_valid
        from candidates c cross join caller
        left join subscriptions s on s.id=c.subscription_id
        left join stripe_billing_failed_grace_boundary_ready after_ready
         on after_ready.id=c.after_ready_id and after_ready.preparation_id=c.preparation and after_ready.admission_id=c.id and after_ready.phase=3
        left join stripe_billing_failed_grace_boundary_ready boundary
         on boundary.preparation_id=c.preparation and boundary.admission_id=c.id and boundary.phase=c.phase
        """;
}
