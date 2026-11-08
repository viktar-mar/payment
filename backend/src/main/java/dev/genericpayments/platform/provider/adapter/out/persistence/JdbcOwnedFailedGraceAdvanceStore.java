package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.GraceLastSecondPreparation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedGraceAdvanceStore;
import jakarta.persistence.EntityManagerFactory;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit, non-registered store on the authenticated JPA caller's actual connection only. */
public final class JdbcOwnedFailedGraceAdvanceStore implements OwnedFailedGraceAdvanceStore {
    private final JdbcTemplate jdbc;private final EntityManagerFactory factory;private final JdbcOwnedFailedRenewalStore failures;
    public JdbcOwnedFailedGraceAdvanceStore(JdbcTemplate jdbc,EntityManagerFactory factory){this.jdbc=Objects.requireNonNull(jdbc);this.factory=Objects.requireNonNull(factory);failures=new JdbcOwnedFailedRenewalStore(jdbc,factory);}
    @Override public void beforeAuthorityInTransaction(InvocationBudget b){
        caller();if(b==null)throw denied();long left=b.remainingNanos(null);if(left<1_000_000)throw denied();limits(left/1_000_000,false);b.remainingNanos(null);
    }
    @Override public ProviderOperation firstClaimInTransaction(PreparationContext c,String claimant,Instant now,InvocationBudget b){
        positive(c,b,now,null);if(!dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.validClaimant(claimant))throw denied();
        var p=c.preparation();var a=c.account();var id=UUID.randomUUID();var at=micro(now);
        // Plain INSERT: every existing state, including RESERVED, is a permanent refusal.
        jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint)
            values(?,?,?,?,'OWNED_GRACE_CLOCK_ADVANCE',?,?,?)
            """,id,a.id().value(),a.externalAccountId(),a.credentialsRef(),p.id(),p.operationKey(),p.fingerprint());
        jdbc.update("""
            insert into stripe_billing_failed_grace_claims(preparation_id,operation_id,invocation,claimant,entered_at,deadline_at,claimed_at,deadline)
            values(?,?,?,?,?,?,?,?)
            """,p.id(),id,b.invocation(),claimant,stamp(b.enteredAt()),stamp(b.deadlineAt()),stamp(at),stamp(at.plusSeconds(60)));
        var claim=one(jdbc.query("""
            update provider_operations set status='DISPATCHING',first_dispatched_at=?,retry_before=?,lease_owner=?,lease_expires_at=?,version=1
            where id=? and status='RESERVED' and version=0 returning *
            """,this::row,stamp(at),stamp(at.plusSeconds(23*3600)),claimant,stamp(at.plusSeconds(60)),id));
        b.remainingNanos(claim);return claim;
    }
    @Override public ProviderOperation requireClaimInTransaction(PreparationContext c,ProviderOperation claim,Instant now,InvocationBudget b){
        positive(c,b,now,claim);owned(c,claim);var current=lock(c);if(!current.equals(claim)||claim.status()!=Status.DISPATCHING||claim.version()!=1)throw denied();
        require(original(c,claim,b,true));b.remainingNanos(claim);return current;
    }
    @Override public AdvanceAck acknowledgeInTransaction(PreparationContext c,ProviderOperation claim,TestClockAck observation,Instant observedAt,Instant now,InvocationBudget b){
        requireClaimInTransaction(c,claim,now,b);var at=micro(now);
        var operation=one(jdbc.query("""
            update provider_operations set status='ACKNOWLEDGED',provider_reference=?,lease_owner=null,lease_expires_at=null,version=2
            where id=? and status='DISPATCHING' and version=1 and lease_owner=? and lease_expires_at=? returning *
            """,this::row,observation.reference(),claim.id(),claim.leaseOwner(),stamp(claim.leaseExpiresAt())));
        jdbc.update("""
            insert into stripe_billing_failed_grace_acks(preparation_id,operation_id,invocation,claimant,deadline,reference,object_type,livemode,name,frozen_time,status,provider_created,provider_deletes_after,advancing_target,observed_at,acknowledged_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,c.preparation().id(),claim.id(),b.invocation(),claim.leaseOwner(),stamp(claim.leaseExpiresAt()),observation.reference(),observation.object(),observation.livemode(),observation.name(),observation.frozenTime(),observation.status(),observation.created(),observation.deletesAfter(),observation.advancingTarget(),stamp(observedAt),stamp(at));
        b.remainingNanos(claim);return ack(c,operation);
    }
    @Override public AdvanceAck requireAcknowledgedInTransaction(PreparationContext c,AdvanceAck supplied,Instant now,InvocationBudget b){
        positive(c,b,now,null);if(supplied==null)throw denied();owned(c,supplied.operation());var operation=lock(c);
        if(!operation.equals(supplied.operation())||!original(c,operation,b,true))throw denied();var saved=ack(c,operation);
        if(!saved.equals(supplied))throw denied();
        require(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from stripe_billing_failed_grace_acks where preparation_id=? and recorded_transaction<>pg_current_xact_id()) and not exists(select 1 from stripe_billing_failed_grace_ready where preparation_id=?)",Boolean.class,c.preparation().id(),c.preparation().id())));
        b.remainingNanos(null);return saved;
    }
    @Override public GraceAdvanceReady recordReadyInTransaction(PreparationContext c,AdvanceAck supplied,TestClockReady observation,Instant observedAt,Instant now,InvocationBudget b){
        requireAcknowledgedInTransaction(c,supplied,now,b);var id=UUID.randomUUID();
        jdbc.update("""
            insert into stripe_billing_failed_grace_ready(id,preparation_id,admission_id,operation_id,invocation,reference,object_type,livemode,name,frozen_time,status,provider_created,provider_deletes_after,advancing_target,observed_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,c.preparation().id(),c.admission().id(),supplied.operation().id(),b.invocation(),observation.reference(),observation.object(),observation.livemode(),observation.name(),observation.frozenTime(),observation.status(),observation.created(),observation.deletesAfter(),observation.advancingTarget(),stamp(observedAt));
        b.remainingNanos(null);return new GraceAdvanceReady(id,c.admission().id(),supplied,observation,micro(observedAt));
    }
    @Override public void uncertainInTransaction(PreparationContext c,ProviderOperation originalClaim,AdvanceAck suppliedAck,InvocationBudget b,Instant now){
        caller();limits(2000,true);if(c==null||b==null||now==null)throw denied();immutable(c);owned(c,originalClaim);
        if(originalClaim.status()!=Status.DISPATCHING||originalClaim.version()!=1||originalClaim.firstDispatchedAt()==null||originalClaim.leaseOwner()==null||originalClaim.leaseExpiresAt()==null)throw denied();
        jdbc.queryForList("select lock_owned_failed_grace_cleanup(?)",c.preparation().id());var current=lock(c);
        if(!current.id().equals(originalClaim.id())||!original(c,current,b,false))return;
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from stripe_billing_failed_grace_claims where preparation_id=? and operation_id=? and claimant=? and claimed_at=? and deadline=?)",Boolean.class,c.preparation().id(),originalClaim.id(),originalClaim.leaseOwner(),stamp(originalClaim.firstDispatchedAt()),stamp(originalClaim.leaseExpiresAt()))))return;
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from stripe_billing_failed_grace_ready where preparation_id=?) or exists(select 1 from stripe_billing_failed_grace_cleanup where preparation_id=?)",Boolean.class,c.preparation().id(),c.preparation().id())))return;
        if(current.status()==Status.DISPATCHING){
            if(!current.equals(originalClaim)||suppliedAck!=null)return;
            var rows=jdbc.query("update provider_operations set status='UNKNOWN',lease_owner=null,lease_expires_at=null,version=2 where id=? and status='DISPATCHING' and version=1 returning *",this::row,current.id());if(rows.size()!=1)throw denied();
        }else if(current.status()==Status.ACKNOWLEDGED){
            // An ambiguous return with no caller ACK preserves consumed evidence conservatively.
            if(suppliedAck==null||!ack(c,current).equals(suppliedAck))return;
        }else return;
        var at=micro(now);
        jdbc.update("insert into stripe_billing_failed_grace_cleanup(preparation_id,operation_id,invocation,stopped_at) values(?,?,?,?)",c.preparation().id(),originalClaim.id(),b.invocation(),stamp(at));
        jdbc.update("insert into stripe_billing_failed_renewal_stops(admission_id,reason,stopped_at) values(?,'UNCERTAIN',?)",c.admission().id(),stamp(at));
    }
    private void caller(){OwnedGraceCallerConnection.require(jdbc,factory,"Owned failed grace advance requires caller DataSource transaction");}
    private void limits(long millis,boolean cleanup){jdbc.queryForList("select owned_failed_grace_limits(?,?)",millis,cleanup);}
    private void positive(PreparationContext c,InvocationBudget b,Instant now,ProviderOperation claim){
        caller();if(c==null||b==null||now==null)throw denied();long left=b.remainingNanos(claim);if(left<1_000_000)throw denied();limits(left/1_000_000,false);
        if(now.isBefore(b.enteredAt())||!now.isBefore(b.deadlineAt()))throw denied();
        var p=failures.requireGraceLastSecondInTransaction(c.admission(),c.preparation());if(!p.equals(c.preparation())||!failures.requireFailureInTransaction(c.admission()).equals(c.failure()))throw denied();
        jdbc.queryForList("select lock_owned_failed_grace_advance(?)",c.preparation().id());
        require(Boolean.TRUE.equals(jdbc.queryForObject("select owned_failed_grace_current(?) and ?<=clock_timestamp() and clock_timestamp()<?",Boolean.class,c.admission().id(),stamp(now),stamp(b.deadlineAt()))));b.remainingNanos(claim);
    }
    private void immutable(PreparationContext c){
        var a=c.admission();JdbcOwnedFirstRenewalStore.requireStoredFailedIdentity(jdbc,a.firstRenewalAdmission(),a.ready(),a.boundary());
        if(!failures.storedIdentityForStop(a).equals(a)||!failures.failureRow(a.id()).equals(c.failure()))throw denied();
        var p=jdbc.query("select * from stripe_billing_failed_grace_preparations where id=? and admission_id=?",(r,n)->new GraceLastSecondPreparation(r.getObject("id",UUID.class),r.getObject("admission_id",UUID.class),r.getObject("failure_claim_id",UUID.class),r.getObject("renewal_bill",UUID.class),r.getObject("predecessor_ready",UUID.class),instant(r,"grace_deadline"),r.getLong("target"),r.getString("operation_key"),r.getString("fingerprint"),instant(r,"prepared_at")),c.preparation().id(),a.id());
        if(p.size()!=1||!p.getFirst().equals(c.preparation()))throw denied();require(Boolean.TRUE.equals(jdbc.queryForObject("select owned_failed_grace_identity(?)",Boolean.class,c.preparation().id())));
    }
    private void owned(PreparationContext c,ProviderOperation o){if(o==null||o.kind()!=Kind.OWNED_GRACE_CLOCK_ADVANCE||!o.accountId().equals(c.account().id())||!o.externalAccountId().equals(c.account().externalAccountId())||!o.credentialsRef().equals(c.account().credentialsRef())||!o.resourceId().equals(c.preparation().id())||!o.operationKey().equals(c.preparation().operationKey())||!o.fingerprint().equals(c.preparation().fingerprint()))throw denied();}
    private ProviderOperation lock(PreparationContext c){return one(jdbc.query("select * from provider_operations where payment_account_id=? and kind='OWNED_GRACE_CLOCK_ADVANCE' and resource_id=? for update nowait",this::row,c.account().id().value(),c.preparation().id()));}
    private boolean original(PreparationContext c,ProviderOperation o,InvocationBudget b,boolean live){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from stripe_billing_failed_grace_claims q where q.preparation_id=? and q.operation_id=? and q.invocation=?
             and q.entered_at=? and q.deadline_at=? and q.claimed_at=? and q.recorded_transaction<>pg_current_xact_id()
             and (?=false or (clock_timestamp()<q.deadline_at and clock_timestamp()<q.deadline)))
            """,Boolean.class,c.preparation().id(),o.id(),b.invocation(),stamp(b.enteredAt()),stamp(b.deadlineAt()),stamp(o.firstDispatchedAt()),live));
    }
    private AdvanceAck ack(PreparationContext c,ProviderOperation operation){var rows=jdbc.query("select * from stripe_billing_failed_grace_acks where preparation_id=? and operation_id=?",(r,n)->new AdvanceAck(operation,c.preparation().id(),r.getObject("invocation",UUID.class),r.getString("claimant"),instant(r,"deadline"),new TestClockAck(r.getString("reference"),r.getString("object_type"),r.getBoolean("livemode"),r.getString("name"),r.getLong("frozen_time"),r.getString("status"),r.getLong("provider_created"),r.getLong("provider_deletes_after"),r.getObject("advancing_target",Long.class)),instant(r,"observed_at"),instant(r,"acknowledged_at")),c.preparation().id(),operation.id());if(rows.size()!=1)throw denied();return rows.getFirst();}
    private ProviderOperation row(ResultSet r,int n)throws SQLException{return new ProviderOperation(r.getObject("id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getString("external_account_id"),r.getString("credentials_ref"),Kind.valueOf(r.getString("kind")),r.getObject("resource_id",UUID.class),r.getString("operation_key"),r.getString("fingerprint"),Status.valueOf(r.getString("status")),r.getString("provider_reference"),instant(r,"first_dispatched_at"),instant(r,"retry_before"),r.getString("lease_owner"),instant(r,"lease_expires_at"),r.getLong("version"));}
    private static Instant instant(ResultSet r,String name)throws SQLException{var value=r.getTimestamp(name);return value==null?null:value.toInstant();}
    private static Instant micro(Instant at){return at.truncatedTo(ChronoUnit.MICROS);}
    private static Timestamp stamp(Instant at){return Timestamp.from(micro(at));}
    private static ProviderOperation one(List<ProviderOperation> rows){if(rows.size()!=1)throw denied();return rows.getFirst();}
    private static void require(boolean condition){if(!condition)throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("OWNED_FAILED_GRACE_ADVANCE_UNAVAILABLE");}
}
