package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.PreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceBoundary.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Admission;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.GraceLastSecondPreparation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedGraceBoundaryStore;

import jakarta.persistence.EntityManagerFactory;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit, non-registered store on the authenticated JPA caller's actual connection only. */
public final class JdbcOwnedFailedGraceBoundaryStore implements OwnedFailedGraceBoundaryStore {
    private final JdbcTemplate jdbc;private final EntityManagerFactory factory;private final JdbcOwnedFailedRenewalStore failures;
    public JdbcOwnedFailedGraceBoundaryStore(JdbcTemplate jdbc,EntityManagerFactory factory){this.jdbc=Objects.requireNonNull(jdbc);this.factory=Objects.requireNonNull(factory);failures=new JdbcOwnedFailedRenewalStore(jdbc,factory);}
    @Override public void beforeAuthorityInTransaction(InvocationBudget b){
        caller();if(b==null)throw denied();long left=b.remainingNanos(null);if(left<1_000_000)throw denied();limits(left/1_000_000,false);b.remainingNanos(null);
    }
    @Override public void beforeFreshEntryInTransaction(Admission admission,GraceLastSecondPreparation preparation,Phase phase,InvocationBudget b){
        beforeAuthorityInTransaction(b);
        if(admission==null||preparation==null||phase==null)throw denied();
        immutablePreparation(admission,preparation);
        beforeAuthorityInTransaction(b);
        // READ COMMITTED gives the following existence query its own snapshot. This mutex
        // serializes fresh entrants while remaining compatible with continuation's KEY SHARE.
        require(Boolean.TRUE.equals(jdbc.queryForObject("select owned_grace_boundary_fresh(?,?)",Boolean.class,preparation.id(),phase.number())));
        b.remainingNanos(null);
    }
    @Override public ProviderOperation firstClaimInTransaction(BoundaryContext c,String claimant,Instant now,InvocationBudget b){
        var writer=writingCaller();
        if(c==null)throw denied();
        // Direct JDBC writers share the same entry mutex through commit. Reacquisition by
        // the service's transaction is reentrant and keeps the plain INSERT mandatory.
        beforeFreshEntryInTransaction(c.original().admission(),c.original().preparation(),c.phase(),b);
        positive(c,b,now,null);if(!OwnedFailedGraceAdvance.validClaimant(claimant))throw denied();
        var p=c.original().preparation();var a=c.original().account();var id=UUID.randomUUID();var at=micro(now);
        // Plain INSERT: every existing state, including RESERVED, is a permanent refusal.
        jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint)
            values(?,?,?,?,?,?,?,?)
            """,id,a.id().value(),a.externalAccountId(),a.credentialsRef(),c.phase().kind().name(),p.id(),c.operationKey(),c.fingerprint());
        jdbc.update("""
            insert into stripe_billing_failed_grace_boundary_claims(preparation_id,admission_id,phase,original_grace_ready_id,predecessor_phase,predecessor_ready_id,operation_id,invocation,claimant,entered_at,deadline_at,claimed_at,deadline)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,p.id(),c.original().admission().id(),c.phase().number(),c.originalGraceReadyId(),c.phase()==Phase.AFTER?2:null,c.phase()==Phase.AFTER?c.predecessor().readyId():null,id,b.invocation(),claimant,stamp(b.enteredAt()),stamp(b.deadlineAt()),stamp(at),stamp(at.plusSeconds(60)));
        var claim=one(jdbc.query("""
            update provider_operations set status='DISPATCHING',first_dispatched_at=?,retry_before=?,lease_owner=?,lease_expires_at=?,version=1
            where id=? and status='RESERVED' and version=0 returning *
            """,this::row,stamp(at),stamp(at.plusSeconds(23*3600)),claimant,stamp(at.plusSeconds(60)),id));
        b.remainingNanos(claim);completePositive(writer,c,claim.id(),b,claim,"claim");return claim;
    }
    @Override public ProviderOperation requireClaimInTransaction(BoundaryContext c,ProviderOperation claim,Instant now,InvocationBudget b){
        positive(c,b,now,claim);owned(c,claim);var current=lock(c);if(!current.equals(claim)||claim.status()!=Status.DISPATCHING||claim.version()!=1)throw denied();
        require(original(c,claim,b,true));b.remainingNanos(claim);return current;
    }
    @Override public BoundaryAck acknowledgeInTransaction(BoundaryContext c,ProviderOperation claim,TestClockAck observation,Instant observedAt,Instant now,InvocationBudget b){
        var writer=writingCaller();
        requireClaimInTransaction(c,claim,now,b);var at=micro(now);
        var operation=one(jdbc.query("""
            update provider_operations set status='ACKNOWLEDGED',provider_reference=?,lease_owner=null,lease_expires_at=null,version=2
            where id=? and status='DISPATCHING' and version=1 and lease_owner=? and lease_expires_at=? returning *
            """,this::row,observation.reference(),claim.id(),claim.leaseOwner(),stamp(claim.leaseExpiresAt())));
        jdbc.update("""
            insert into stripe_billing_failed_grace_boundary_acks(preparation_id,phase,operation_id,invocation,claimant,deadline,reference,object_type,livemode,name,frozen_time,status,provider_created,provider_deletes_after,advancing_target,observed_at,acknowledged_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,c.original().preparation().id(),c.phase().number(),claim.id(),b.invocation(),claim.leaseOwner(),stamp(claim.leaseExpiresAt()),observation.reference(),observation.object(),observation.livemode(),observation.name(),observation.frozenTime(),observation.status(),observation.created(),observation.deletesAfter(),observation.advancingTarget(),stamp(observedAt),stamp(at));
        b.remainingNanos(claim);var saved=ack(c,operation);completePositive(writer,c,claim.id(),b,claim,"ack");return saved;
    }
    @Override public BoundaryAck requireAcknowledgedInTransaction(BoundaryContext c,BoundaryAck supplied,Instant now,InvocationBudget b){
        positive(c,b,now,null);if(supplied==null)throw denied();owned(c,supplied.operation());var operation=lock(c);
        if(!operation.equals(supplied.operation())||!original(c,operation,b,true))throw denied();var saved=ack(c,operation);
        if(!saved.equals(supplied))throw denied();
        require(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from stripe_billing_failed_grace_boundary_acks where operation_id=? and producer_xid<>pg_current_xact_id()) and not exists(select 1 from stripe_billing_failed_grace_boundary_ready where operation_id=?)",Boolean.class,operation.id(),operation.id())));
        b.remainingNanos(null);return saved;
    }
    @Override public BoundaryReady recordReadyInTransaction(BoundaryContext c,BoundaryAck supplied,TestClockReady observation,Instant observedAt,Instant now,InvocationBudget b){
        var writer=writingCaller();
        requireAcknowledgedInTransaction(c,supplied,now,b);var id=UUID.randomUUID();
        jdbc.update("""
            insert into stripe_billing_failed_grace_boundary_ready(id,preparation_id,phase,admission_id,operation_id,invocation,reference,object_type,livemode,name,frozen_time,status,provider_created,provider_deletes_after,advancing_target,observed_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,c.original().preparation().id(),c.phase().number(),c.original().admission().id(),supplied.operation().id(),b.invocation(),observation.reference(),observation.object(),observation.livemode(),observation.name(),observation.frozenTime(),observation.status(),observation.created(),observation.deletesAfter(),observation.advancingTarget(),stamp(observedAt));
        b.remainingNanos(null);var ready=new BoundaryReady(id,c.original().admission().id(),supplied,observation,micro(observedAt));
        completePositive(writer,c,supplied.operation().id(),b,null,"ready");return ready;
    }
    @Override public void uncertainInTransaction(BoundaryContext c,ProviderOperation originalClaim,BoundaryAck suppliedAck,InvocationBudget b,Instant now){
        var writer=writingCaller();limits(2000,true);if(c==null||b==null||now==null)throw denied();immutable(c);owned(c,originalClaim);
        var dispatched=originalClaim.firstDispatchedAt();
        if(originalClaim.status()!=Status.DISPATCHING||originalClaim.version()!=1||originalClaim.providerReference()!=null
                ||dispatched==null||!dispatched.equals(micro(dispatched))||!dispatched.plusSeconds(23*3600).equals(originalClaim.retryBefore())
                ||!OwnedFailedGraceAdvance.validClaimant(originalClaim.leaseOwner())
                ||!dispatched.plusSeconds(60).equals(originalClaim.leaseExpiresAt()))throw denied();
        jdbc.queryForList("select lock_owned_grace_boundary(?,?,true)",c.original().preparation().id(),c.phase().number());var current=lock(c);
        if(!current.id().equals(originalClaim.id())||!original(c,current,b,false))return;
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from stripe_billing_failed_grace_boundary_claims where preparation_id=? and operation_id=? and claimant=? and claimed_at=? and deadline=?)",Boolean.class,c.original().preparation().id(),originalClaim.id(),originalClaim.leaseOwner(),stamp(originalClaim.firstDispatchedAt()),stamp(originalClaim.leaseExpiresAt()))))return;
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from stripe_billing_failed_grace_boundary_ready where operation_id=?) or exists(select 1 from stripe_billing_failed_grace_boundary_cleanup where operation_id=?)",Boolean.class,current.id(),current.id())))return;
        if(current.status()==Status.DISPATCHING){
            if(!current.equals(originalClaim)||suppliedAck!=null)return;
            var rows=jdbc.query("update provider_operations set status='UNKNOWN',lease_owner=null,lease_expires_at=null,version=2 where id=? and status='DISPATCHING' and version=1 returning *",this::row,current.id());if(rows.size()!=1)throw denied();
        }else if(current.status()==Status.ACKNOWLEDGED){
            // Only the exact original committed invocation may consume saved ACK identity after
            // an ambiguous return. This never grants positive inspection or polling authority.
            var saved=ack(c,current);
            if(!saved.invocation().equals(b.invocation())||!saved.claimant().equals(originalClaim.leaseOwner())||!saved.deadline().equals(originalClaim.leaseExpiresAt())
                    ||suppliedAck!=null&&!saved.equals(suppliedAck))return;
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from stripe_billing_failed_grace_boundary_acks where preparation_id=? and operation_id=? and invocation=? and producer_xid<>pg_current_xact_id())",Boolean.class,c.original().preparation().id(),current.id(),b.invocation())))return;
        }else return;
        // Application UTC may have rolled backwards. Choose one actual database instant only
        // after locking and authenticating the original identity, and reuse it for both inserts.
        var at=micro(jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant());
        jdbc.update("insert into stripe_billing_failed_grace_boundary_cleanup(preparation_id,phase,operation_id,invocation,claimant,deadline,stopped_at) values(?,?,?,?,?,?,?)",c.original().preparation().id(),c.phase().number(),originalClaim.id(),b.invocation(),originalClaim.leaseOwner(),stamp(originalClaim.leaseExpiresAt()),stamp(at));
        jdbc.update("insert into stripe_billing_failed_renewal_stops(admission_id,reason,stopped_at) values(?,'UNCERTAIN',?)",c.original().admission().id(),stamp(at));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){
                var manager=writer.requireReadCommitted();if(readOnly)throw denied();
                limits(2000,true);manager.flush();writer.requireReadCommitted();
                require(Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from stripe_billing_failed_grace_boundary_cleanup u
                    join stripe_billing_failed_renewal_stops s on s.admission_id=? and s.stopped_at=u.stopped_at
                    where u.preparation_id=? and u.phase=? and u.operation_id=? and u.invocation=?
                    and u.claimant=? and u.deadline=? and u.producer_xid=pg_current_xact_id()
                    and s.reason='UNCERTAIN' and s.xmin=pg_current_xact_id()::xid)
                    """,Boolean.class,c.original().admission().id(),c.original().preparation().id(),c.phase().number(),
                    current.id(),b.invocation(),originalClaim.leaseOwner(),stamp(originalClaim.leaseExpiresAt()))));
            }
        });
    }
    private static final String CALLER_REQUIRED="Owned grace boundary requires caller DataSource transaction";
    private void caller(){OwnedGraceCallerConnection.capture(jdbc,factory,CALLER_REQUIRED).requireReadCommitted();}
    private OwnedGraceCallerConnection writingCaller(){
        var writer=OwnedGraceCallerConnection.capture(jdbc,factory,CALLER_REQUIRED);
        writer.requireReadCommitted();
        if(!TransactionSynchronizationManager.isSynchronizationActive())throw denied();
        return writer;
    }
    void completePositive(OwnedGraceCallerConnection writer,BoundaryContext c,UUID operation,
            InvocationBudget b,ProviderOperation lease,String phase){
        writer.requireReadCommitted();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){
                var manager=writer.requireReadCommitted();
                if(readOnly)throw denied();
                limits(completionMillis(b,lease),false);
                manager.flush();
                writer.requireReadCommitted();
                limits(completionMillis(b,lease),false);
                var proof=jdbc.queryForList("""
                    select require_owned_boundary_positive(q) from stripe_billing_failed_grace_boundary_claims q
                    join provider_operations o on o.id=q.operation_id
                    left join stripe_billing_failed_grace_boundary_acks a on a.operation_id=q.operation_id
                    left join stripe_billing_failed_grace_boundary_ready r on r.operation_id=q.operation_id
                    where q.preparation_id=? and q.operation_id=? and q.invocation=? and q.entered_at=? and q.deadline_at=?
                      and owned_grace_boundary_claim_shape(q,o)
                      and exists(select 1 from payment_accounts account
                        where account.id=o.payment_account_id and account.id=? and account.account_key=?)
                      and case cast(? as text)
                        when 'claim' then q.producer_xid=pg_current_xact_id() and o.status='DISPATCHING' and o.version=1
                        when 'ack' then a.producer_xid=pg_current_xact_id() and o.status='ACKNOWLEDGED' and o.version=2
                        when 'ready' then r.producer_xid=pg_current_xact_id() and o.status='ACKNOWLEDGED' and o.version=2
                        else false end
                    """,c.original().preparation().id(),operation,b.invocation(),stamp(b.enteredAt()),stamp(b.deadlineAt()),
                    c.original().account().id().value(),c.original().account().key(),phase);
                require(proof.size()==1);
                // Check again after the final SQL round trip, without starting another
                // positive statement between this original-budget check and completion.
                if(completionMillis(b,lease)==0)limits(0,false);
            }
        });
    }
    private static long completionMillis(InvocationBudget b,ProviderOperation lease){
        try{return b.remainingNanos(lease)/1_000_000;}
        // Zero invokes only the existing 23514 refusal under the previously installed
        // transaction limits. No new window or positive work follows an invalid budget.
        catch(IllegalStateException expired){return 0;}
    }
    private void limits(long millis,boolean cleanup){jdbc.queryForList("select owned_failed_grace_limits(?,?)",millis,cleanup);}
    private void positive(BoundaryContext c,InvocationBudget b,Instant now,ProviderOperation claim){
        caller();if(c==null||b==null||now==null)throw denied();long left=b.remainingNanos(claim);if(left<1_000_000)throw denied();limits(left/1_000_000,false);
        if(now.isBefore(b.enteredAt())||!now.isBefore(b.deadlineAt()))throw denied();
        if(!c.equals(requirePredecessorInTransaction(c.original(),c.phase(),b)))throw denied();
        require(Boolean.TRUE.equals(jdbc.queryForObject("select ?<=clock_timestamp() and clock_timestamp()<?",Boolean.class,stamp(now),stamp(b.deadlineAt()))));
        b.remainingNanos(claim);
    }
    @Override public BoundaryContext requirePredecessorInTransaction(PreparationContext original,Phase phase,InvocationBudget b){
        beforeAuthorityInTransaction(b);if(original==null||phase==null)throw denied();
        var p=original.preparation();immutablePreparation(original.admission(),p);
        if(!failures.failureRow(original.admission().id()).equals(original.failure()))throw denied();
        jdbc.queryForList("select lock_owned_grace_boundary(?,?,false)",p.id(),phase.number());
        var context=committedEdge(original,phase,b);
        require(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from payment_accounts where id=? and account_key=?)",
            Boolean.class,original.account().id().value(),original.account().key())));
        require(!micro(b.enteredAt()).isBefore(context.predecessor().observedAt()));b.remainingNanos(null);return context;
    }
    /** Nonlocking snapshot description. It confers no permission to mutate without the locked recheck. */
    BoundaryContext committedEdge(PreparationContext original,Phase phase,InvocationBudget b){
        caller();return edge(original,phase,Objects.requireNonNull(b).invocation());
    }
    private BoundaryContext edge(PreparationContext original,Phase phase,UUID invocation){
        // Fresh callers have no claim to exclude. During a continuation, derive the one
        // permitted operation from the exact invocation and persisted claim. A null invocation
        // requests only the immutable edge description for cleanup.
        var rows=jdbc.query("""
            select e.*, case when i.invocation is null then true else
              owned_grace_boundary_writer_exclusive(i.preparation,i.phase,(
                select q.operation_id from stripe_billing_failed_grace_boundary_claims q
                join provider_operations o on o.id=q.operation_id
                where q.preparation_id=i.preparation and q.phase=i.phase and q.invocation=i.invocation
                  and owned_grace_boundary_claim_shape(q,o))) end as phase_exclusive
            from (values(cast(? as uuid),cast(? as integer),cast(? as uuid))) i(preparation,phase,invocation)
            cross join lateral owned_grace_boundary_edge(i.preparation,i.phase) e
            where i.invocation is null or owned_grace_predecessor_committed(i.preparation,i.phase,clock_timestamp(),pg_current_xact_id())
            """,(r,n)->{
                require(r.getBoolean("phase_exclusive"));
                return new BoundaryContext(original,phase,r.getObject("original_id",UUID.class),
                    new ReadyOrigin(r.getObject("previous_id",UUID.class),r.getObject("operation_id",UUID.class),r.getObject("invocation",UUID.class),
                        r.getString("operation_fingerprint"),new TestClockReady(r.getString("reference"),r.getString("object_type"),r.getBoolean("livemode"),
                        r.getString("name"),r.getLong("frozen_time"),r.getString("status"),r.getLong("provider_created"),r.getLong("provider_deletes_after"),
                        r.getObject("advancing_target",Long.class)),instant(r,"observed_at")),r.getLong("target"),r.getString("operation_key"),r.getString("fingerprint"));
            },original.preparation().id(),phase.number(),invocation);
        if(rows.size()!=1)throw denied();return rows.getFirst();
    }
    private void immutable(BoundaryContext c){
        immutablePreparation(c.original().admission(),c.original().preparation());
        if(!c.equals(edge(c.original(),c.phase(),null))||!failures.failureRow(c.original().admission().id()).equals(c.original().failure()))throw denied();
    }
    private void immutablePreparation(Admission a,GraceLastSecondPreparation preparation){
        JdbcOwnedFirstRenewalStore.requireStoredFailedIdentity(jdbc,a.firstRenewalAdmission(),a.ready(),a.boundary());
        if(!failures.storedIdentityForStop(a).equals(a))throw denied();
        var p=jdbc.query("select * from stripe_billing_failed_grace_preparations where id=? and admission_id=?",(r,n)->new GraceLastSecondPreparation(r.getObject("id",UUID.class),r.getObject("admission_id",UUID.class),r.getObject("failure_claim_id",UUID.class),r.getObject("renewal_bill",UUID.class),r.getObject("predecessor_ready",UUID.class),instant(r,"grace_deadline"),r.getLong("target"),r.getString("operation_key"),r.getString("fingerprint"),instant(r,"prepared_at")),preparation.id(),a.id());
        if(p.size()!=1||!p.getFirst().equals(preparation))throw denied();
        require(Boolean.TRUE.equals(jdbc.queryForObject("select owned_failed_grace_identity(?)",Boolean.class,preparation.id())));
    }
    private void owned(BoundaryContext c,ProviderOperation o){if(o==null||o.kind()!=c.phase().kind()||!o.accountId().equals(c.original().account().id())||!o.externalAccountId().equals(c.original().account().externalAccountId())||!o.credentialsRef().equals(c.original().account().credentialsRef())||!o.resourceId().equals(c.original().preparation().id())||!o.operationKey().equals(c.operationKey())||!o.fingerprint().equals(c.fingerprint()))throw denied();}
    private ProviderOperation lock(BoundaryContext c){return one(jdbc.query("select * from provider_operations where payment_account_id=? and kind=? and resource_id=? for update nowait",this::row,c.original().account().id().value(),c.phase().kind().name(),c.original().preparation().id()));}
    private boolean original(BoundaryContext c,ProviderOperation o,InvocationBudget b,boolean live){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from stripe_billing_failed_grace_boundary_claims q join provider_operations o on o.id=q.operation_id where owned_grace_boundary_claim_shape(q,o) and q.phase=? and q.preparation_id=? and q.operation_id=? and q.invocation=?
             and q.entered_at=? and q.deadline_at=? and q.claimed_at=? and q.producer_xid<>pg_current_xact_id()
             and case when ? then clock_timestamp()<q.deadline_at and clock_timestamp()<q.deadline
               and owned_grace_boundary_writer_exclusive(q.preparation_id,q.phase,q.operation_id) else true end)
            """,Boolean.class,c.phase().number(),c.original().preparation().id(),o.id(),b.invocation(),stamp(b.enteredAt()),stamp(b.deadlineAt()),stamp(o.firstDispatchedAt()),live));
    }
    private BoundaryAck ack(BoundaryContext c,ProviderOperation operation){var rows=jdbc.query("select a.* from stripe_billing_failed_grace_boundary_acks a join stripe_billing_failed_grace_boundary_claims q using(operation_id) join provider_operations o on o.id=a.operation_id where q.preparation_id=? and q.operation_id=? and owned_grace_boundary_ack_shape(q,a,o,clock_timestamp())",(r,n)->new BoundaryAck(c.phase(),c.original().preparation().id(),operation,r.getObject("invocation",UUID.class),r.getString("claimant"),instant(r,"deadline"),new TestClockAck(r.getString("reference"),r.getString("object_type"),r.getBoolean("livemode"),r.getString("name"),r.getLong("frozen_time"),r.getString("status"),r.getLong("provider_created"),r.getLong("provider_deletes_after"),r.getObject("advancing_target",Long.class)),instant(r,"observed_at"),instant(r,"acknowledged_at")),c.original().preparation().id(),operation.id());if(rows.size()!=1)throw denied();return rows.getFirst();}
    private ProviderOperation row(ResultSet r,int n)throws SQLException{return new ProviderOperation(r.getObject("id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getString("external_account_id"),r.getString("credentials_ref"),Kind.valueOf(r.getString("kind")),r.getObject("resource_id",UUID.class),r.getString("operation_key"),r.getString("fingerprint"),Status.valueOf(r.getString("status")),r.getString("provider_reference"),instant(r,"first_dispatched_at"),instant(r,"retry_before"),r.getString("lease_owner"),instant(r,"lease_expires_at"),r.getLong("version"));}
    private static Instant instant(ResultSet r,String name)throws SQLException{var value=r.getTimestamp(name);return value==null?null:value.toInstant();}
    private static Instant micro(Instant at){return at.truncatedTo(ChronoUnit.MICROS);}
    private static Timestamp stamp(Instant at){return Timestamp.from(micro(at));}
    private static ProviderOperation one(List<ProviderOperation> rows){if(rows.size()!=1)throw denied();return rows.getFirst();}
    private static void require(boolean condition){if(!condition)throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("OWNED_FAILED_GRACE_BOUNDARY_UNAVAILABLE");}
}
