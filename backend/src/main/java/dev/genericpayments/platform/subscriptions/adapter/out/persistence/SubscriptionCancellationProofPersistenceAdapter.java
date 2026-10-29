package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCancellationProofStore;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Original result and ACK commit together. All provider HTTP precedes this short transaction. */
@Repository
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class SubscriptionCancellationProofPersistenceAdapter implements SubscriptionCancellationProofStore {
    private final JdbcTemplate jdbc;
    private final ProviderOperationStore steps;
    public SubscriptionCancellationProofPersistenceAdapter(JdbcTemplate jdbc,ProviderOperationStore steps){this.jdbc=jdbc;this.steps=steps;}
    public Optional<Receipt> receipt(PaymentAccountId account,UUID operation){
        return jdbc.query("select * from stripe_subscription_cancellation_proofs where payment_account_id=? and operation_id=?",this::receipt,account.value(),operation).stream().findFirst();
    }
    public Optional<Receipt> activeSchedule(PaymentAccountId account,UUID subscription,Instant boundary){
        var rows=jdbc.query("""
            select p.* from stripe_subscription_cancellation_proofs p
            where p.payment_account_id=? and p.subscription_id=? and p.mode='PERIOD_END' and p.boundary=?
              and not exists(select 1 from stripe_subscription_cancellation_proofs w where w.prior_operation=p.operation_id)
            order by p.acknowledged_at,p.operation_id limit 2
            """,this::receipt,account.value(),subscription,Timestamp.from(boundary));
        if(rows.size()>1)throw conflict();return rows.stream().findFirst();
    }
    public void authorize(Subscription s,SubscriptionOperation o){lockAndRequire(s,o,null);}
    public Optional<ObservedSchedule> observedSchedule(PaymentAccountId account,UUID subscription,Instant boundary){
        var rows=jdbc.query("""
            select e.id,e.revision from subscriptions s join subscription_events e on e.subscription_id=s.id
              and e.site_id=s.site_id and e.payment_account_id=s.payment_account_id and e.provider_reference=s.provider_reference
            where s.id=? and s.payment_account_id=? and s.cancellation_effective_at=? and e.revision=s.revision
              and e.event_type='CANCEL_SCHEDULED' and s.billing_status='ACTIVE' and s.pending_operation_id is null
              and s.debt_billing_record_id is null and s.trial_end is null and s.paid_through=?
              and s.current_period_end=? and s.current_period_start<=clock_timestamp() and ? > clock_timestamp()
              and not exists(select 1 from subscription_events later where later.subscription_id=s.id
                and later.sequence_number>e.sequence_number and later.event_type in ('CANCEL_SCHEDULED','CANCEL_WITHDRAWN','CANCELLED'))
              and not exists(select 1 from stripe_subscription_cancellation_proofs used where used.prior_event=e.id)
            """,(r,n)->new ObservedSchedule(r.getObject(1,UUID.class),r.getLong(2),boundary),subscription,account.value(),Timestamp.from(boundary),Timestamp.from(boundary),Timestamp.from(boundary),Timestamp.from(boundary));
        if(rows.size()>1)throw conflict();return rows.stream().findFirst();
    }
    public void requireObserved(Subscription s,ObservedSchedule observed){
        if(observed==null||observed.revision()!=s.revision()||s.cancellation()==null||!observed.boundary().equals(s.cancellation().effectiveAt())
            ||!observedSchedule(s.paymentAccountId(),s.id(),observed.boundary()).filter(observed::equals).isPresent())throw conflict();
    }
    public Receipt record(Subscription s,SubscriptionOperation o,ProviderOperation step,String owner,Mode mode,Instant boundary,UUID prior,Instant received){
        return record(s,o,step,owner,mode,boundary,prior,received,null);
    }
    public Receipt recordObserved(Subscription s,SubscriptionOperation o,ProviderOperation step,String owner,ObservedSchedule observed,Instant received){
        return record(s,o,step,owner,Mode.WITHDRAW,observed.boundary(),null,received,observed);
    }
    private Receipt record(Subscription s,SubscriptionOperation o,ProviderOperation step,String owner,Mode mode,Instant boundary,UUID prior,Instant received,ObservedSchedule observed){
        lockAndRequire(s,o,step);
        var expected=SubscriptionCancellationTransaction.boundary(o).orElse(null);
        if(!Objects.equals(boundary,expected)||received==null||received.getNano()%1000!=0||step.firstDispatchedAt()==null||step.retryBefore()==null
            ||received.isBefore(step.firstDispatchedAt())||!received.isBefore(step.retryBefore())
            ||mode!=Mode.IMMEDIATE&&(boundary==null||boundary.getNano()!=0||!received.isBefore(boundary)))throw conflict();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select ?::timestamptz<=clock_timestamp()",Boolean.class,Timestamp.from(received))))throw conflict();
        if(mode==Mode.WITHDRAW){
            if(o.kind()!=SubscriptionOperation.Kind.WITHDRAW_CANCEL||(prior==null)==(observed==null))throw conflict();
            if(observed!=null)requireObserved(s,observed);
            else if(!activeSchedule(s.paymentAccountId(),s.id(),boundary).filter(p->p.operation().equals(prior)).isPresent())throw conflict();
        }else if(o.kind()!=SubscriptionOperation.Kind.CANCEL||prior!=null||(mode==Mode.IMMEDIATE)!=SubscriptionCancellationTransaction.immediate(o))throw conflict();
        var kind=mode==Mode.IMMEDIATE?ProviderOperation.Kind.SUBSCRIPTION_CANCEL:ProviderOperation.Kind.SUBSCRIPTION_UPDATE;
        if(step.kind()!=kind||!step.resourceId().equals(o.id())||!step.accountId().equals(s.paymentAccountId())||!step.operationKey().equals(o.providerOperationKey()))throw conflict();
        steps.acknowledgeInTransaction(step.id(),owner,step.version(),s.providerReference());
        jdbc.update("""
            insert into stripe_subscription_cancellation_proofs(operation_id,site_id,customer_id,subscription_id,payment_account_id,
                operation_kind,step_id,step_kind,subscription_reference,mode,boundary,prior_operation,acknowledged_at,prior_event,prior_event_revision)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(operation_id) do nothing
            """,o.id(),s.siteId().value(),s.customerId().value(),s.id(),s.paymentAccountId().value(),o.kind().name(),step.id(),kind.name(),s.providerReference(),mode.name(),boundary==null?null:Timestamp.from(boundary),prior,Timestamp.from(received),observed==null?null:observed.event(),observed==null?null:observed.revision());
        return receipt(s.paymentAccountId(),o.id()).filter(r->r.subscription().equals(s.id())&&r.step().equals(step.id())&&r.reference().equals(s.providerReference())
            &&r.mode()==mode&&Objects.equals(r.boundary(),boundary)&&Objects.equals(r.priorOperation(),prior)&&Objects.equals(r.observed(),observed)&&r.acknowledgedAt().equals(received)).orElseThrow(SubscriptionCancellationProofPersistenceAdapter::conflict);
    }
    private void lockAndRequire(Subscription s,SubscriptionOperation o,ProviderOperation step){
        if(!Set.of(SubscriptionOperation.Kind.CANCEL,SubscriptionOperation.Kind.WITHDRAW_CANCEL).contains(o.kind())
            ||!s.id().equals(o.subscriptionId())||!s.siteId().equals(o.siteId())||!s.customerId().equals(o.customerId()))throw conflict();
        if(jdbc.queryForList("select id from subscription_operations where id=? and version=? and fingerprint=? and provider_operation_key=? and status in ('PROCESSING','UNKNOWN') for update",UUID.class,o.id(),o.version(),o.fingerprint(),o.providerOperationKey()).size()!=1)throw conflict();
        jdbc.queryForList("select id from sites where id=? for update",UUID.class,s.siteId().value());
        jdbc.queryForList("select id from customers where site_id=? and id=? for update",UUID.class,s.siteId().value(),s.customerId().value());
        // Ordinary claim/ACK holds Step before Account too. Never acquire a Step behind Account.
        if(step!=null)steps.lockSubscriptionMutationReceipt(step);
        jdbc.queryForList("select id from payment_accounts where id=? for update",UUID.class,s.paymentAccountId().value());
        jdbc.queryForList("select id from subscriptions where site_id=? and id=? for update",UUID.class,s.siteId().value(),s.id());
        if(jdbc.queryForList("""
            select s.id from subscriptions s join subscription_operations o on o.subscription_id=s.id
            join sites site on site.id=s.site_id join customers c on c.site_id=s.site_id and c.id=s.customer_id
            join payment_accounts a on a.id=s.payment_account_id join site_subscription_policies p on p.site_id=s.site_id
            join provider_operations step on step.resource_id=o.id and step.payment_account_id=a.id and step.operation_key=o.provider_operation_key
            where s.id=? and o.id=? and s.payment_account_id=? and s.provider_reference=? and s.revision=?
              and s.billing_price_id=? and s.billing_price_version=? and s.billing_status<>'CANCELLED' and s.pending_operation_id is null
              and a.provider='STRIPE' and a.account_mode='SANDBOX' and a.account_status='ACTIVE'
              and step.external_account_id=a.external_account_id and step.credentials_ref=a.credentials_ref
              and step.status='DISPATCHING' and step.lease_expires_at>clock_timestamp()
              and site.status='ACTIVE' and c.status='ACTIVE' and p.enabled and p.version=?
              and not exists(select 1 from subscription_operations other where other.site_id=s.site_id and other.subscription_id=s.id
                  and other.id<>o.id and other.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and other.kind not in ('PORTAL','RECONCILE'))
              and ((o.kind='CANCEL' and ((o.fingerprint like 'v1.cancel.period_end.%' and s.cancellation_effective_at is null)
                    or (o.fingerprint like 'v1.cancel.immediate.none.%' and (p.allow_immediate_cancellation
                        or (s.billing_status in ('PAUSED','UNPAID','INCOMPLETE') and (s.paid_through is null or s.paid_through<=clock_timestamp())
                            and (s.trial_end is null or s.trial_end<=clock_timestamp()))))))
                or (o.kind='WITHDRAW_CANCEL' and s.cancellation_effective_at=?))
            """,UUID.class,s.id(),o.id(),s.paymentAccountId().value(),s.providerReference(),o.expectedRevision(),s.billingPrice().priceId().value(),s.billingPrice().version(),o.policyVersion(),SubscriptionCancellationTransaction.boundary(o).map(Timestamp::from).orElse(null)).size()!=1)throw conflict();
    }
    private Receipt receipt(ResultSet r,int row)throws SQLException{
        var boundary=r.getTimestamp("boundary");
        var event=r.getObject("prior_event",UUID.class);
        return new Receipt(r.getObject("operation_id",UUID.class),r.getObject("subscription_id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getObject("step_id",UUID.class),r.getString("subscription_reference"),Mode.valueOf(r.getString("mode")),boundary==null?null:boundary.toInstant(),r.getObject("prior_operation",UUID.class),r.getTimestamp("acknowledged_at").toInstant(),event==null?null:new ObservedSchedule(event,r.getLong("prior_event_revision"),boundary.toInstant()));
    }
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider evidence does not match");}
}
