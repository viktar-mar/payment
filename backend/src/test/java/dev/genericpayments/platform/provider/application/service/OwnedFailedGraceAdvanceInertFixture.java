package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedGraceAdvanceStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Test-only bridge: the existing real failed-renewal service and FactService remain the authority.
 * Only the new append-only receipts are in memory. No SDK, datasource, runtime or IO exists here. */
public final class OwnedFailedGraceAdvanceInertFixture {
    final OwnedFailedRenewalServiceTest.Harness h=new OwnedFailedRenewalServiceTest.Harness(false,"alias",900000);
    final MemoryStore store=new MemoryStore();
    public final OwnedFailedGraceAdvanceService service;
    public final PreparationContext context;
    public final InvocationBudget budget;
    long nanos;long transactionId;Runnable beforeCommit=()->{},afterCommit=()->{};
    public OwnedFailedGraceAdvanceInertFixture(){this(0);}
    public OwnedFailedGraceAdvanceInertFixture(long enteredNanos){
        h.fail();var p=h.service.prepareGraceLastSecond(h.admission);
        h.utc=h.utc.plusNanos(enteredNanos);
        var tx=new SubscriptionTransactionPort(){
            public boolean active(){return h.active;}
            public <T>T required(Supplier<T> effect){return requiresNew(effect);}
            public <T>T requiresNew(Supplier<T> effect){
                var old=store.snapshot();transactionId++;boolean committed=false;
                try{
                    var result=h.transactionPort.requiresNew(()->{var value=effect.get();beforeCommit.run();return value;});
                    store.committedTransaction=Math.max(store.committedTransaction,Math.max(store.claimTransaction,store.ackTransaction));committed=true;afterCommit.run();return result;
                }catch(RuntimeException|Error failure){if(!committed)store.restore(old);throw failure;}
            }
        };
        service=new OwnedFailedGraceAdvanceService(h.service,store,tx,h.clockPort);
        budget=newBudget();context=service.inspect(h.admission,p,budget);h.trace.clear();store.calls.clear();
    }
    public InvocationBudget newBudget(){return new InvocationBudget(h.clockPort,()->nanos);}
    public Clock clock(){return h.clockPort;}
    public void at(Instant value){h.utc=value;}
    public Instant now(){return h.utc;}
    public void elapsed(long value){nanos=value;}
    public List<String> trace(){return h.trace;}
    public ProviderOperation claim(){return service.claim(context,"grace-original",budget);}
    public TestClockAck post(){return observation(context.preparation().target(),"advancing",context.preparation().target());}
    public TestClockReady ready(){var c=context.admission().ready().observation();return new TestClockReady(c.reference(),c.object(),c.livemode(),c.name(),context.preparation().target(),"ready",c.created(),c.deletesAfter(),null);}
    public TestClockAck observation(long target,String status,Long advancing){var c=context.admission().ready().observation();return new TestClockAck(c.reference(),c.object(),c.livemode(),c.name(),target,status,c.created(),c.deletesAfter(),advancing);}
    public AdvanceAck acknowledge(ProviderOperation claim){return service.acknowledge(context,claim,post(),now(),budget);}
    public Object financialState(){return Arrays.asList(h.s,new LinkedHashMap<>(h.ops),new LinkedHashMap<>(h.records),new LinkedHashSet<>(h.cash),new ArrayList<>(h.events),h.effects,h.callbacks,h.sequence,h.billingSecond,h.failure,h.preparation);}
    public ProviderOperation row(){return store.row;}
    public AdvanceAck ack(){return store.ack;}
    public GraceAdvanceReady savedReady(){return store.ready;}
    public int stops(){return store.stops;}
    public void afterEffect(Runnable effect){store.afterEffect=effect;}
    public boolean active(){return h.active;}
    static ProviderOperation copy(ProviderOperation o,ProviderOperation.Status status,long version,String reference,String owner,Instant lease){return new ProviderOperation(o.id(),o.accountId(),o.externalAccountId(),o.credentialsRef(),o.kind(),o.resourceId(),o.operationKey(),o.fingerprint(),status,reference,o.firstDispatchedAt(),o.retryBefore(),owner,lease,version);}

    final class MemoryStore implements OwnedFailedGraceAdvanceStore {
        ProviderOperation row;AdvanceAck ack;GraceAdvanceReady ready;InvocationBudget original;String claimant;
        long claimTransaction,ackTransaction,committedTransaction;int stops,writes;boolean failAck,failReady,failLimiter;
        Runnable afterEffect=()->{},afterLock=()->{};final List<String> calls=new ArrayList<>();
        record State(ProviderOperation row,AdvanceAck ack,GraceAdvanceReady ready,InvocationBudget budget,String claimant,long claimTx,long ackTx,long committed,int stops,int writes){}
        State snapshot(){return new State(row,ack,ready,original,claimant,claimTransaction,ackTransaction,committedTransaction,stops,writes);}
        void restore(State s){row=s.row;ack=s.ack;ready=s.ready;original=s.budget;claimant=s.claimant;claimTransaction=s.claimTx;ackTransaction=s.ackTx;committedTransaction=s.committed;stops=s.stops;writes=s.writes;}
        void boundary(String effect){assertThat(h.active).isTrue();calls.add(effect);h.trace.add("grace-"+effect);afterLock.run();}
        public void beforeAuthorityInTransaction(InvocationBudget b){boundary("limit");if(failLimiter)throw new IllegalStateException("caller limiter unavailable");b.remainingNanos(null);}
        void exact(PreparationContext c,ProviderOperation supplied,InvocationBudget b){
            assertThat(c).isEqualTo(context);if(original!=b||row==null||!row.equals(supplied)||claimTransaction==transactionId||committedTransaction<claimTransaction)throw new IllegalStateException("original committed claim required");
        }
        public ProviderOperation firstClaimInTransaction(PreparationContext c,String owner,Instant now,InvocationBudget b){
            boundary("firstclaim");assertThat(c).isEqualTo(context);assertThat(b).isSameAs(budget);
            if(row!=null)throw new IllegalStateException("consumed firstclaim");var p=c.preparation();var a=c.account();var at=now.truncatedTo(ChronoUnit.MICROS);
            row=new ProviderOperation(UUID.randomUUID(),a.id(),a.externalAccountId(),a.credentialsRef(),ProviderOperation.Kind.OWNED_GRACE_CLOCK_ADVANCE,p.id(),p.operationKey(),p.fingerprint(),ProviderOperation.Status.DISPATCHING,null,at,at.plusSeconds(23*3600),owner,at.plusSeconds(60),1);
            original=b;claimant=owner;claimTransaction=transactionId;writes++;afterEffect.run();return row;
        }
        public ProviderOperation requireClaimInTransaction(PreparationContext c,ProviderOperation supplied,Instant now,InvocationBudget b){boundary("claim");exact(c,supplied,b);if(row.status()!=ProviderOperation.Status.DISPATCHING)throw new IllegalStateException("not dispatching");afterEffect.run();return row;}
        public AdvanceAck acknowledgeInTransaction(PreparationContext c,ProviderOperation supplied,TestClockAck observation,Instant observedAt,Instant now,InvocationBudget b){
            boundary("ack");exact(c,supplied,b);if(row.status()!=ProviderOperation.Status.DISPATCHING||ack!=null)throw new IllegalStateException("not original dispatch");
            row=copy(row,ProviderOperation.Status.ACKNOWLEDGED,2,observation.reference(),null,null);
            ack=new AdvanceAck(row,c.preparation().id(),b.invocation(),claimant,supplied.leaseExpiresAt(),observation,observedAt.truncatedTo(ChronoUnit.MICROS),now.truncatedTo(ChronoUnit.MICROS));ackTransaction=transactionId;writes++;afterEffect.run();if(failAck)throw new IllegalStateException("append ack failed");return ack;
        }
        void exactAck(PreparationContext c,AdvanceAck supplied,InvocationBudget b){exact(c,supplied.operation(),b);if(ack==null||!ack.equals(supplied)||ackTransaction==transactionId||committedTransaction<ackTransaction||ready!=null)throw new IllegalStateException("original committed ack required");}
        public AdvanceAck requireAcknowledgedInTransaction(PreparationContext c,AdvanceAck supplied,Instant now,InvocationBudget b){boundary("requireAck");exactAck(c,supplied,b);afterEffect.run();return ack;}
        public GraceAdvanceReady recordReadyInTransaction(PreparationContext c,AdvanceAck supplied,TestClockReady observation,Instant observedAt,Instant now,InvocationBudget b){
            boundary("ready");exactAck(c,supplied,b);ready=new GraceAdvanceReady(UUID.randomUUID(),c.admission().id(),supplied,observation,observedAt.truncatedTo(ChronoUnit.MICROS));writes++;afterEffect.run();if(failReady)throw new IllegalStateException("append ready failed");return ready;
        }
        public void uncertainInTransaction(PreparationContext c,ProviderOperation supplied,AdvanceAck suppliedAck,InvocationBudget b,Instant now){
            boundary("cleanup");if(row==null||original!=b||claimTransaction==transactionId||committedTransaction<claimTransaction||ready!=null||!row.id().equals(supplied.id())||!c.equals(context))return;
            if(ack==null){if(!row.equals(supplied)||row.status()!=ProviderOperation.Status.DISPATCHING||suppliedAck!=null)return;row=copy(row,ProviderOperation.Status.UNKNOWN,2,null,null,null);}
            else if(!ack.equals(suppliedAck)||!ack.operation().equals(row))return;
            if(stops==0){stops++;writes++;}afterEffect.run();
        }
    }
}
