package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.PreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceBoundary.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.GraceLastSecondPreparation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedGraceBoundaryStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

/** Explicit internal producer, with provider work entirely outside the caller transactions. */
public final class OwnedFailedGraceBoundaryService {
    private final OwnedFailedRenewalService failures;private final OwnedFailedGraceBoundaryStore store;
    private final SubscriptionTransactionPort transactions;private final Clock utc;
    public OwnedFailedGraceBoundaryService(OwnedFailedRenewalService failures,OwnedFailedGraceBoundaryStore store,SubscriptionTransactionPort transactions,Clock utc){this.failures=Objects.requireNonNull(failures);this.store=Objects.requireNonNull(store);this.transactions=Objects.requireNonNull(transactions);this.utc=Objects.requireNonNull(utc);}
    public BoundaryContext inspect(dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Admission admission,GraceLastSecondPreparation p,Phase phase,InvocationBudget budget){
        fresh();Objects.requireNonNull(phase);checkpoint(budget,null,null);
        return transactions.requiresNew(()->{
            store.beforeAuthorityInTransaction(budget);checkpoint(budget,null,null);
            store.beforeFreshEntryInTransaction(admission,p,phase,budget);checkpoint(budget,null,null);
            var c=failures.withGraceLastSecondInTransaction(admission,p,original->predecessor(original,phase,budget));
            checkpoint(budget,null,null);return c;
        });
    }
    public ProviderOperation claim(BoundaryContext c,String claimant,InvocationBudget b){
        fresh();if(!OwnedFailedGraceAdvance.validClaimant(claimant))throw denied();
        return transition(c,b,null,null,actual->{var o=store.firstClaimInTransaction(actual,claimant,utc.instant(),b);requireClaim(actual,o);if(!claimant.equals(o.leaseOwner())||o.firstDispatchedAt().isBefore(micro(b.enteredAt()))||o.firstDispatchedAt().isBefore(actual.predecessor().observedAt())||o.firstDispatchedAt().isAfter(utc.instant()))throw denied();return o;});
    }
    public ProviderOperation inspectClaim(BoundaryContext c,ProviderOperation claim,InvocationBudget b){
        fresh();requireClaim(c,claim);return transition(c,b,claim,null,actual->{var saved=store.requireClaimInTransaction(actual,claim,utc.instant(),b);if(!claim.equals(saved))throw denied();return saved;});
    }
    public BoundaryAck acknowledge(BoundaryContext c,ProviderOperation claim,TestClockAck observation,Instant observedAt,InvocationBudget b){
        fresh();requireClaim(c,claim);requirePost(c,claim,observation,observedAt);
        return transition(c,b,claim,null,actual->{
            requirePost(actual,claim,observation,observedAt);
            var ack=store.acknowledgeInTransaction(actual,claim,observation,observedAt,utc.instant(),b);requireAck(actual,ack,b);
            if(!sameOperation(claim,ack.operation())||!ack.claimant().equals(claim.leaseOwner())||!ack.deadline().equals(claim.leaseExpiresAt())||!ack.observation().equals(observation)||!ack.observedAt().equals(micro(observedAt))||ack.acknowledgedAt().isAfter(utc.instant()))throw denied();return ack;
        });
    }
    public BoundaryAck inspectAck(BoundaryContext c,BoundaryAck ack,InvocationBudget b){
        fresh();requireAck(c,ack,b);return transition(c,b,null,ack,actual->{var saved=store.requireAcknowledgedInTransaction(actual,ack,utc.instant(),b);if(!ack.equals(saved))throw denied();return saved;});
    }
    public BoundaryReady recordReady(BoundaryContext c,BoundaryAck ack,TestClockReady observation,Instant observedAt,InvocationBudget b){
        fresh();requireAck(c,ack,b);requireReady(c,ack,observation,observedAt);
        return transition(c,b,null,ack,actual->{
            requireReady(actual,ack,observation,observedAt);var ready=store.recordReadyInTransaction(actual,ack,observation,observedAt,utc.instant(),b);
            if(!ready.admissionId().equals(actual.original().admission().id())||!ready.ack().equals(ack)||!ready.observation().equals(observation)||!ready.observedAt().equals(micro(observedAt)))throw denied();return ready;
        });
    }
    public void uncertain(BoundaryContext c,ProviderOperation claim,BoundaryAck ack,InvocationBudget b){
        fresh();try{
            if(b==null)throw denied();requireClaim(c,claim);if(ack!=null){requireAck(c,ack,b);if(!sameOperation(claim,ack.operation())||!ack.claimant().equals(claim.leaseOwner())||!ack.deadline().equals(claim.leaseExpiresAt()))throw denied();}
            // Only immutable original identity enters cleanup; no positive callback, clock renewal or current authority.
            transactions.requiresNew(()->{store.uncertainInTransaction(c,claim,ack,b,utc.instant());return null;});
        }catch(RuntimeException ignored){/* Conservatively preserve consumed evidence when cleanup cannot commit. */}
    }
    private <T>T transition(BoundaryContext expected,InvocationBudget b,ProviderOperation claim,BoundaryAck ack,Function<BoundaryContext,T> effect){
        fresh();Objects.requireNonNull(expected);checkpoint(b,claim,ack);
        return transactions.requiresNew(()->{
            store.beforeAuthorityInTransaction(b);checkpoint(b,claim,ack);
            if(claim==null&&ack==null){
                store.beforeFreshEntryInTransaction(expected.original().admission(),expected.original().preparation(),expected.phase(),b);checkpoint(b,null,null);
            }
            var result=failures.withGraceLastSecondInTransaction(expected.original().admission(),expected.original().preparation(),original->{
                var actual=predecessor(original,expected.phase(),b);
                if(!actual.equals(expected))throw denied();
                checkpoint(b,claim,ack);
                var value=effect.apply(actual);
                checkpoint(b,claim,ack);
                store.beforeAuthorityInTransaction(b);
                if(!expected.equals(predecessor(original,expected.phase(),b)))throw denied();
                checkpoint(b,claim,ack);return value;
            });
            // Final precommit checkpoint, with no post-commit check that could erase a valid late return.
            checkpoint(b,claim,ack);return result;
        });
    }
    private BoundaryContext predecessor(PreparationContext original,Phase phase,InvocationBudget budget){
        var context=store.requirePredecessorInTransaction(original,phase,budget);
        if(context==null||!context.original().equals(original)||context.phase()!=phase
                ||budget.enteredAt().isBefore(context.predecessor().observedAt()))throw denied();
        return context;
    }
    private void checkpoint(InvocationBudget b,ProviderOperation claim,BoundaryAck ack){
        if(b==null)throw denied();b.remainingNanos(claim);var now=utc.instant();
        if(now.isBefore(b.enteredAt())||!now.isBefore(b.deadlineAt())||claim!=null&&(!now.isBefore(claim.retryBefore())||!now.isBefore(claim.leaseExpiresAt()))||ack!=null&&(!now.isBefore(ack.deadline())||!ack.invocation().equals(b.invocation())))throw denied();
    }
    private static void identity(BoundaryContext c,ProviderOperation o){
        if(c==null||o==null||o.id()==null||o.kind()!=c.phase().kind()||!o.accountId().equals(c.original().account().id())||!o.externalAccountId().equals(c.original().account().externalAccountId())||!o.credentialsRef().equals(c.original().account().credentialsRef())||!o.resourceId().equals(c.original().preparation().id())||!o.operationKey().equals(c.operationKey())||!o.fingerprint().equals(c.fingerprint()))throw denied();
    }
    private static void requireClaim(BoundaryContext c,ProviderOperation o){
        identity(c,o);if(o.status()!=Status.DISPATCHING||o.version()!=1||o.providerReference()!=null||o.firstDispatchedAt()==null||o.firstDispatchedAt().getNano()%1000!=0||!OwnedFailedGraceAdvance.validClaimant(o.leaseOwner())||o.leaseExpiresAt()==null||!o.leaseExpiresAt().equals(o.firstDispatchedAt().plusSeconds(60))||o.retryBefore()==null||!o.retryBefore().equals(o.firstDispatchedAt().plusSeconds(23*3600)))throw denied();
    }
    private static void requireAck(BoundaryContext c,BoundaryAck ack,InvocationBudget b){
        if(c==null||ack==null||b==null||ack.phase()!=c.phase()||!ack.invocation().equals(b.invocation()))throw denied();identity(c,ack.operation());
        var old=c.predecessor().observation();var post=ack.observation();
        if(!OwnedFailedGraceAdvance.sameClock(post,old)||!validPost(c,post)||!ack.acknowledgedAt().isBefore(micro(b.deadlineAt()))||ack.operation().firstDispatchedAt().isBefore(micro(b.enteredAt())))throw denied();
    }
    private void requirePost(BoundaryContext c,ProviderOperation claim,TestClockAck post,Instant observedAt){
        if(post==null||observedAt==null||!OwnedFailedGraceAdvance.sameClock(post,c.predecessor().observation())||!validPost(c,post)||observedAt.isBefore(claim.firstDispatchedAt())||observedAt.isAfter(utc.instant()))throw denied();
    }
    private static boolean validPost(BoundaryContext c,TestClockAck post){
        long target=c.target(),previous=c.predecessor().observation().frozenTime();
        return "ready".equals(post.status())?post.frozenTime()==target&&(post.advancingTarget()==null||post.advancingTarget()==target):"advancing".equals(post.status())&&post.frozenTime()>=previous&&post.frozenTime()<=target&&(post.advancingTarget()==null||post.advancingTarget()==target);
    }
    private void requireReady(BoundaryContext c,BoundaryAck ack,TestClockReady ready,Instant observedAt){if(ready==null||observedAt==null||ready.frozenTime()!=c.target()||!OwnedFailedGraceAdvance.sameClock(ack.observation(),ready)||observedAt.isBefore(ack.acknowledgedAt())||observedAt.isAfter(utc.instant()))throw denied();}
    private static boolean sameOperation(ProviderOperation a,ProviderOperation b){return a.id().equals(b.id())&&a.accountId().equals(b.accountId())&&a.externalAccountId().equals(b.externalAccountId())&&a.credentialsRef().equals(b.credentialsRef())&&a.kind()==b.kind()&&a.resourceId().equals(b.resourceId())&&a.operationKey().equals(b.operationKey())&&a.fingerprint().equals(b.fingerprint())&&a.firstDispatchedAt().equals(b.firstDispatchedAt())&&a.retryBefore().equals(b.retryBefore());}
    private static Instant micro(Instant value){return value.truncatedTo(ChronoUnit.MICROS);}
    private void fresh(){if(transactions.active())throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("OWNED_FAILED_GRACE_BOUNDARY_UNAVAILABLE");}
}

