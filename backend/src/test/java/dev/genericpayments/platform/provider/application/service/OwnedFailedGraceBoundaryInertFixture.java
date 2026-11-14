package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.PreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceBoundary.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Admission;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.GraceLastSecondPreparation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockAck;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockReady;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedGraceBoundaryStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual failed renewal + original producer, and a handwritten transactional boundary for the two new receipts. */
public final class OwnedFailedGraceBoundaryInertFixture implements OwnedFailedGraceBoundaryStore {
    public final OwnedFailedGraceAdvanceInertFixture base=new OwnedFailedGraceAdvanceInertFixture(0,"stripe-sandbox-default");
    public final OwnedFailedGraceBoundaryService service;
    private final Map<Phase,Row> rows=new EnumMap<>(Phase.class);
    public record Row(ProviderOperation claim,InvocationBudget budget,BoundaryAck ack,BoundaryReady ready,boolean stopped){}
    public Runnable afterWrite=()->{},afterCommit=()->{};
    final Thread caller=Thread.currentThread();
    public int cleanups;
    public OwnedFailedGraceBoundaryInertFixture(){
        var ack=base.acknowledge(base.claim());
        base.service.recordReady(base.context,ack,base.ready(),base.now(),base.budget);
        // Old lease + invocation are expired before the new producer enters.
        base.at(base.now().plusSeconds(90));
        var tx=new SubscriptionTransactionPort(){
            public boolean active(){return base.active();}
            public <T>T required(Supplier<T> work){return requiresNew(work);}
            public <T>T requiresNew(Supplier<T> work){
                var before=new EnumMap<>(rows);
                T value;
                try{value=base.h.transactionPort.requiresNew(work);}
                catch(RuntimeException|Error failure){rows.clear();rows.putAll(before);throw failure;}
                afterCommit.run();return value;
            }
        };
        service=new OwnedFailedGraceBoundaryService(base.h.service,this,tx,base.clock());
        base.trace().clear();
    }
    public BoundaryContext inspect(Phase phase,InvocationBudget budget){return service.inspect(base.context.admission(),base.context.preparation(),phase,budget);}
    public Row row(Phase phase){return rows.get(phase);}
    void boundary(String name){assertThat(Thread.currentThread()).isSameAs(caller);assertThat(base.active()).isTrue();base.trace().add(name);}
    public void beforeAuthorityInTransaction(InvocationBudget b){boundary("limit");b.remainingNanos(null);}
    public void beforeFreshEntryInTransaction(Admission a,GraceLastSecondPreparation p,Phase phase,InvocationBudget b){
        boundary("fresh");if(!a.equals(base.context.admission())||!p.equals(base.context.preparation())||rows.containsKey(phase))throw new IllegalStateException();
    }
    public BoundaryContext requirePredecessorInTransaction(PreparationContext original,Phase phase,InvocationBudget b){
        boundary("predecessor");var root=base.savedReady();ReadyOrigin previous;
        if(phase==Phase.AT){
            var a=root.ack();
            previous=new ReadyOrigin(root.id(),a.operation().id(),a.invocation(),a.operation().fingerprint(),root.observation(),root.observedAt());
        }else{
            var parent=rows.get(Phase.AT);
            if(parent==null||parent.ready()==null||parent.stopped())throw new IllegalStateException();
            var r=parent.ready();var a=r.ack();
            previous=new ReadyOrigin(r.id(),a.operation().id(),a.invocation(),a.operation().fingerprint(),r.observation(),r.observedAt());
        }
        return OwnedFailedGraceBoundary.context(original,phase,root.id(),previous);
    }
    public ProviderOperation firstClaimInTransaction(BoundaryContext c,String owner,Instant now,InvocationBudget b){
        boundary("firstclaim");if(rows.containsKey(c.phase()))throw new IllegalStateException();var account=c.original().account();
        var claim=new ProviderOperation(UUID.randomUUID(),account.id(),account.externalAccountId(),account.credentialsRef(),c.phase().kind(),
            c.original().preparation().id(),c.operationKey(),c.fingerprint(),ProviderOperation.Status.DISPATCHING,null,now,now.plusSeconds(82800),owner,now.plusSeconds(60),1);
        rows.put(c.phase(),new Row(claim,b,null,null,false));afterWrite.run();return claim;
    }
    Row own(BoundaryContext c,InvocationBudget b){var row=rows.get(c.phase());if(row==null||row.budget()!=b||row.stopped()||row.ready()!=null)throw new IllegalStateException();return row;}
    public ProviderOperation requireClaimInTransaction(BoundaryContext c,ProviderOperation claim,Instant now,InvocationBudget b){
        boundary("inspectClaim");var row=own(c,b);if(!row.claim().equals(claim)||row.ack()!=null)throw new IllegalStateException();return claim;
    }
    public BoundaryAck acknowledgeInTransaction(BoundaryContext c,ProviderOperation claim,TestClockAck post,Instant observed,Instant now,InvocationBudget b){
        boundary("ack");requireClaimInTransaction(c,claim,now,b);
        var op=OwnedFailedGraceAdvanceInertFixture.copy(claim,ProviderOperation.Status.ACKNOWLEDGED,2,post.reference(),null,null);
        var ack=new BoundaryAck(c.phase(),claim.resourceId(),op,b.invocation(),claim.leaseOwner(),claim.leaseExpiresAt(),post,observed,now);
        rows.put(c.phase(),new Row(claim,b,ack,null,false));afterWrite.run();return ack;
    }
    public BoundaryAck requireAcknowledgedInTransaction(BoundaryContext c,BoundaryAck ack,Instant now,InvocationBudget b){
        boundary("inspectAck");if(!ack.equals(own(c,b).ack()))throw new IllegalStateException();return ack;
    }
    public BoundaryReady recordReadyInTransaction(BoundaryContext c,BoundaryAck ack,TestClockReady ready,Instant observed,Instant now,InvocationBudget b){
        boundary("ready");var row=own(c,b);if(!ack.equals(row.ack()))throw new IllegalStateException();
        var receipt=new BoundaryReady(UUID.randomUUID(),c.original().admission().id(),ack,ready,observed);
        rows.put(c.phase(),new Row(row.claim(),b,ack,receipt,false));afterWrite.run();return receipt;
    }
    public void uncertainInTransaction(BoundaryContext c,ProviderOperation claim,BoundaryAck ack,InvocationBudget b,Instant now){
        boundary("cleanup");cleanups++;var row=rows.get(c.phase());
        if(row!=null&&row.claim().equals(claim)&&row.budget()==b&&row.ready()==null&&(ack==null||ack.equals(row.ack())))
            rows.put(c.phase(),new Row(claim,b,row.ack(),null,true));
    }
    public BoundaryReady complete(Phase phase){
        var b=base.newBudget();var c=inspect(phase,b);var claim=service.claim(c,"inert-owner",b);
        var post=base.observation(c.target(),"ready",null);var ack=service.acknowledge(c,claim,post,base.now(),b);
        var ready=new TestClockReady(post.reference(),post.object(),false,post.name(),post.frozenTime(),"ready",post.created(),post.deletesAfter(),null);
        return service.recordReady(c,ack,ready,base.now(),b);
    }
}

