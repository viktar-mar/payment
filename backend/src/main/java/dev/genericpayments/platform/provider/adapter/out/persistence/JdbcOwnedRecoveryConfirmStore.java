package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.RecoveryPreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedRecoveryConfirmStore;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit caller-transaction store; never starts/suspends a transaction and never calls a provider. */
public final class JdbcOwnedRecoveryConfirmStore implements OwnedRecoveryConfirmStore {
    private final JdbcTemplate jdbc;
    public JdbcOwnedRecoveryConfirmStore(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    @Override public Optional<ProviderOperation> lockInTransaction(RecoveryPreparationContext c){
        transaction();identity(c);
        var rows=jdbc.query("select * from provider_operations where payment_account_id=? and resource_id=? and kind='OWNED_RECOVERY_INTENT_CONFIRM' for update",this::row,c.account().id().value(),c.recoveryOperation().id());
        if(rows.size()>1)throw denied();if(rows.isEmpty())return Optional.empty();var o=rows.getFirst();requireOwned(c,o);return Optional.of(o);
    }
    @Override public ProviderOperation insertAndClaimInTransaction(RecoveryPreparationContext c,String claimant,Instant now,InvocationBudget budget){
        transaction();identity(c);positive(c,budget,now,null);
        if(claimant==null||claimant.isBlank()||claimant.length()>128)throw denied();
        var id=UUID.randomUUID();var at=now.truncatedTo(ChronoUnit.MICROS);
        String fingerprint=jdbc.queryForObject("select owned_recovery_confirm_fingerprint(?)",String.class,c.reservation().id());
        // A uniqueness violation refuses every pre-existing row; there is deliberately no conflict adoption.
        jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint)
            values(?,?,?,?,'OWNED_RECOVERY_INTENT_CONFIRM',?,?,?)
            """,id,c.account().id().value(),c.account().externalAccountId(),c.account().credentialsRef(),c.recoveryOperation().id(),c.recoveryOperation().providerOperationKey()+":CONFIRM",fingerprint);
        jdbc.update("""
            insert into stripe_billing_failed_recovery_confirm_claims(reservation_id,provider_operation,claimant,claimed_at,deadline,invocation,entered_at,deadline_at)
            values(?,?,?,?,?,?,?,?)
            """,c.reservation().id(),id,claimant,stamp(at),stamp(at.plusSeconds(60)),budget.invocation(),stamp(budget.enteredAt()),stamp(budget.deadlineAt()));
        var claim=one(jdbc.query("""
            update provider_operations set status='DISPATCHING',first_dispatched_at=?,retry_before=?,lease_owner=?,lease_expires_at=?,version=1
            where id=? and status='RESERVED' and version=0 returning *
            """,this::row,stamp(at),stamp(at.plus(Duration.ofHours(23))),claimant,stamp(at.plusSeconds(60)),id));
        positive(c,budget,now,claim);return claim;
    }
    @Override public ProviderOperation requireClaimInTransaction(RecoveryPreparationContext c,ProviderOperation claim,InvocationBudget budget,Instant now){
        transaction();identity(c);requireOwned(c,claim);positive(c,budget,now,claim);
        var current=lockInTransaction(c).filter(claim::equals).orElseThrow(JdbcOwnedRecoveryConfirmStore::denied);
        require("""
            select exists(select 1 from stripe_billing_failed_recovery_confirm_claims receipt
            where receipt.provider_operation=? and receipt.reservation_id=? and receipt.claimant=? and receipt.claimed_at=? and receipt.deadline=?
              and receipt.invocation=? and receipt.entered_at=? and receipt.deadline_at=?
              and receipt.recorded_transaction<>pg_current_xact_id() and clock_timestamp()<receipt.deadline_at and clock_timestamp()<receipt.deadline)
            """,claim.id(),c.reservation().id(),claim.leaseOwner(),stamp(claim.firstDispatchedAt()),stamp(claim.leaseExpiresAt()),budget.invocation(),stamp(budget.enteredAt()),stamp(budget.deadlineAt()));
        if(current.status()!=Status.DISPATCHING||current.version()!=1)throw denied();positive(c,budget,now,claim);return current;
    }
    @Override public ConfirmationAck acknowledgeInTransaction(RecoveryPreparationContext c,ProviderOperation claim,ConfirmReply reply,Instant now,InvocationBudget budget){
        requireClaimInTransaction(c,claim,budget,now);
        var at=now.truncatedTo(ChronoUnit.MICROS);
        var acknowledged=one(jdbc.query("""
            update provider_operations set status='ACKNOWLEDGED',provider_reference=?,lease_owner=null,lease_expires_at=null,version=2
            where id=? and status='DISPATCHING' and version=1 and lease_owner=? and lease_expires_at=? returning *
            """,this::row,reply.paymentIntent(),claim.id(),claim.leaseOwner(),stamp(claim.leaseExpiresAt())));
        jdbc.update("""
            insert into stripe_billing_failed_recovery_confirm_acks(provider_operation,reservation_id,claimant,deadline,payment_intent,customer_reference,method_reference,currency,amount,received,capturable,charge,observed_at,acknowledged_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,claim.id(),c.reservation().id(),claim.leaseOwner(),stamp(claim.leaseExpiresAt()),reply.paymentIntent(),reply.customer(),reply.method(),reply.currency(),reply.amount(),reply.received(),reply.capturable(),reply.charge(),stamp(reply.observedAt()),stamp(at));
        positive(c,budget,now,claim);
        // Return original normalized nanosecond evidence; SQL persists its exact microsecond projection.
        return new ConfirmationAck(acknowledged,reply,now);
    }
    @Override public boolean unknownInTransaction(RecoveryPreparationContext c,ProviderOperation claim){
        transaction();identity(c);requireOwned(c,claim);var current=lockInTransaction(c);
        if(current.isEmpty()||!current.get().equals(claim)||claim.status()!=Status.DISPATCHING||claim.version()!=1)return false;
        require("select exists(select 1 from stripe_billing_failed_recovery_confirm_claims where provider_operation=? and reservation_id=? and claimant=? and claimed_at=? and deadline=? and recorded_transaction<>pg_current_xact_id())",claim.id(),c.reservation().id(),claim.leaseOwner(),stamp(claim.firstDispatchedAt()),stamp(claim.leaseExpiresAt()));
        return jdbc.update("update provider_operations set status='UNKNOWN',lease_owner=null,lease_expires_at=null,version=2 where id=? and status='DISPATCHING' and version=1 and lease_owner=? and lease_expires_at=?",claim.id(),claim.leaseOwner(),stamp(claim.leaseExpiresAt()))==1;
    }
    private void identity(RecoveryPreparationContext c){
        if(c==null)throw denied();
        require("""
            select exists(select 1 from stripe_billing_failed_method_reservations r
            join stripe_billing_failed_renewals d on d.id=r.admission_id
            join stripe_billing_failed_method_recoveries x on x.reservation_id=r.id
            where r.id=? and d.payment_account_id=? and x.operation_id=? and owned_recovery_confirm_identity(r.id))
            """,c.reservation().id(),c.account().id().value(),c.recoveryOperation().id());
    }
    private void requireOwned(RecoveryPreparationContext c,ProviderOperation o){
        if(o==null||o.kind()!=Kind.OWNED_RECOVERY_INTENT_CONFIRM||!o.accountId().equals(c.account().id())||!o.resourceId().equals(c.recoveryOperation().id())
                ||!o.externalAccountId().equals(c.account().externalAccountId())||!o.credentialsRef().equals(c.account().credentialsRef())
                ||!o.operationKey().equals(c.recoveryOperation().providerOperationKey()+":CONFIRM"))throw denied();
        String fingerprint=jdbc.queryForObject("select owned_recovery_confirm_fingerprint(?)",String.class,c.reservation().id());if(!Objects.equals(fingerprint,o.fingerprint()))throw denied();
    }
    private void positive(RecoveryPreparationContext c,InvocationBudget budget,Instant now,ProviderOperation claim){
        if(budget==null||now==null)throw denied();budget.remainingNanos(claim);
        if(now.isBefore(budget.enteredAt())||!now.isBefore(budget.deadlineAt()))throw denied();
        // Inspection grants dispatch authority too: pin its exact origin grant, not only write-trigger paths.
        jdbc.queryForList("select lock_owned_recovery_confirm(?,true)",c.reservation().id());
        require("select owned_recovery_confirm_pre(?) and ?<=clock_timestamp() and clock_timestamp()<?",c.reservation().id(),stamp(now),stamp(budget.deadlineAt()));
    }
    private void require(String sql,Object... args){if(!Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)))throw denied();}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw denied();}
    private static Timestamp stamp(Instant at){return Timestamp.from(at.truncatedTo(ChronoUnit.MICROS));}
    private static ProviderOperation one(List<ProviderOperation> rows){if(rows.size()!=1)throw denied();return rows.getFirst();}
    private ProviderOperation row(ResultSet r,int n)throws SQLException{return new ProviderOperation(r.getObject("id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getString("external_account_id"),r.getString("credentials_ref"),Kind.valueOf(r.getString("kind")),r.getObject("resource_id",UUID.class),r.getString("operation_key"),r.getString("fingerprint"),Status.valueOf(r.getString("status")),r.getString("provider_reference"),instant(r,"first_dispatched_at"),instant(r,"retry_before"),r.getString("lease_owner"),instant(r,"lease_expires_at"),r.getLong("version"));}
    private static Instant instant(ResultSet r,String column)throws SQLException{var value=r.getTimestamp(column);return value==null?null:value.toInstant();}
    private static IllegalStateException denied(){return new IllegalStateException("OWNED_RECOVERY_CONFIRM_UNAVAILABLE");}
}
