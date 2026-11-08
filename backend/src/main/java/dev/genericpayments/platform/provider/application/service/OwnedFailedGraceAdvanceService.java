package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedGraceAdvanceStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

/** Explicit internal producer, with provider work entirely outside the caller transactions. */
public final class OwnedFailedGraceAdvanceService {
    private final OwnedFailedRenewalService failures;private final OwnedFailedGraceAdvanceStore store;
    private final SubscriptionTransactionPort transactions;private final Clock utc;
    public OwnedFailedGraceAdvanceService(OwnedFailedRenewalService failures,OwnedFailedGraceAdvanceStore store,SubscriptionTransactionPort transactions,Clock utc){this.failures=Objects.requireNonNull(failures);this.store=Objects.requireNonNull(store);this.transactions=Objects.requireNonNull(transactions);this.utc=Objects.requireNonNull(utc);}
    public PreparationContext inspect(dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Admission admission,GraceLastSecondPreparation p,InvocationBudget budget){
        fresh();checkpoint(budget,null,null);
        return transactions.requiresNew(()->{
            store.beforeAuthorityInTransaction(budget);checkpoint(budget,null,null);
            store.beforeFreshEntryInTransaction(admission,p,budget);checkpoint(budget,null,null);
            var c=failures.withGraceLastSecondInTransaction(admission,p,Function.identity());checkpoint(budget,null,null);return c;
        });
    }
    public ProviderOperation claim(PreparationContext c,String claimant,InvocationBudget b){
        fresh();if(!OwnedFailedGraceAdvance.validClaimant(claimant))throw denied();
        return transition(c,b,null,null,actual->{var o=store.firstClaimInTransaction(actual,claimant,utc.instant(),b);requireClaim(actual,o);if(!claimant.equals(o.leaseOwner())||o.firstDispatchedAt().isBefore(micro(b.enteredAt()))||o.firstDispatchedAt().isBefore(actual.preparation().preparedAt())||o.firstDispatchedAt().isAfter(utc.instant()))throw denied();return o;});
    }
    public ProviderOperation inspectClaim(PreparationContext c,ProviderOperation claim,InvocationBudget b){
        fresh();requireClaim(c,claim);return transition(c,b,claim,null,actual->{var saved=store.requireClaimInTransaction(actual,claim,utc.instant(),b);if(!claim.equals(saved))throw denied();return saved;});
    }
    public AdvanceAck acknowledge(PreparationContext c,ProviderOperation claim,TestClockAck observation,Instant observedAt,InvocationBudget b){
        fresh();requireClaim(c,claim);requirePost(c,claim,observation,observedAt);
        return transition(c,b,claim,null,actual->{
            requirePost(actual,claim,observation,observedAt);
            var ack=store.acknowledgeInTransaction(actual,claim,observation,observedAt,utc.instant(),b);requireAck(actual,ack,b);
            if(!sameOperation(claim,ack.operation())||!ack.claimant().equals(claim.leaseOwner())||!ack.deadline().equals(claim.leaseExpiresAt())||!ack.observation().equals(observation)||!ack.observedAt().equals(micro(observedAt))||ack.acknowledgedAt().isAfter(utc.instant()))throw denied();return ack;
        });
    }
    public AdvanceAck inspectAck(PreparationContext c,AdvanceAck ack,InvocationBudget b){
        fresh();requireAck(c,ack,b);return transition(c,b,null,ack,actual->{var saved=store.requireAcknowledgedInTransaction(actual,ack,utc.instant(),b);if(!ack.equals(saved))throw denied();return saved;});
    }
    public GraceAdvanceReady recordReady(PreparationContext c,AdvanceAck ack,TestClockReady observation,Instant observedAt,InvocationBudget b){
        fresh();requireAck(c,ack,b);requireReady(c,ack,observation,observedAt);
        return transition(c,b,null,ack,actual->{
            requireReady(actual,ack,observation,observedAt);var ready=store.recordReadyInTransaction(actual,ack,observation,observedAt,utc.instant(),b);
            if(!ready.admissionId().equals(actual.admission().id())||!ready.ack().equals(ack)||!ready.observation().equals(observation)||!ready.observedAt().equals(micro(observedAt)))throw denied();return ready;
        });
    }
    public void uncertain(PreparationContext c,ProviderOperation claim,AdvanceAck ack,InvocationBudget b){
        fresh();try{
            if(b==null)throw denied();requireClaim(c,claim);if(ack!=null){requireAck(c,ack,b);if(!sameOperation(claim,ack.operation())||!ack.claimant().equals(claim.leaseOwner())||!ack.deadline().equals(claim.leaseExpiresAt()))throw denied();}
            // Only immutable original identity enters cleanup; no positive callback, clock renewal or current authority.
            transactions.requiresNew(()->{store.uncertainInTransaction(c,claim,ack,b,utc.instant());return null;});
        }catch(RuntimeException ignored){/* Conservatively preserve consumed evidence when cleanup cannot commit. */}
    }
    private <T>T transition(PreparationContext expected,InvocationBudget b,ProviderOperation claim,AdvanceAck ack,Function<PreparationContext,T> effect){
        fresh();Objects.requireNonNull(expected);checkpoint(b,claim,ack);
        return transactions.requiresNew(()->{
            store.beforeAuthorityInTransaction(b);checkpoint(b,claim,ack);
            if(claim==null&&ack==null){
                store.beforeFreshEntryInTransaction(expected.admission(),expected.preparation(),b);checkpoint(b,null,null);
            }
            var result=failures.withGraceLastSecondInTransaction(expected.admission(),expected.preparation(),actual->{
                if(!actual.equals(expected))throw denied();checkpoint(b,claim,ack);var value=effect.apply(actual);checkpoint(b,claim,ack);store.beforeAuthorityInTransaction(b);checkpoint(b,claim,ack);return value;
            });
            // Final precommit checkpoint, with no post-commit check that could erase a valid late return.
            checkpoint(b,claim,ack);return result;
        });
    }
    private void checkpoint(InvocationBudget b,ProviderOperation claim,AdvanceAck ack){
        if(b==null)throw denied();b.remainingNanos(claim);var now=utc.instant();
        if(now.isBefore(b.enteredAt())||!now.isBefore(b.deadlineAt())||claim!=null&&(!now.isBefore(claim.retryBefore())||!now.isBefore(claim.leaseExpiresAt()))||ack!=null&&(!now.isBefore(ack.deadline())||!ack.invocation().equals(b.invocation())))throw denied();
    }
    private static void identity(PreparationContext c,ProviderOperation o){
        if(c==null||o==null||o.kind()!=Kind.OWNED_GRACE_CLOCK_ADVANCE||!o.accountId().equals(c.account().id())||!o.externalAccountId().equals(c.account().externalAccountId())||!o.credentialsRef().equals(c.account().credentialsRef())||!o.resourceId().equals(c.preparation().id())||!o.operationKey().equals(c.preparation().operationKey())||!o.fingerprint().equals(c.preparation().fingerprint()))throw denied();
    }
    private static void requireClaim(PreparationContext c,ProviderOperation o){
        identity(c,o);if(o.status()!=Status.DISPATCHING||o.version()!=1||o.providerReference()!=null||o.firstDispatchedAt()==null||o.firstDispatchedAt().getNano()%1000!=0||!OwnedFailedGraceAdvance.validClaimant(o.leaseOwner())||o.leaseExpiresAt()==null||!o.leaseExpiresAt().equals(o.firstDispatchedAt().plusSeconds(60))||o.retryBefore()==null||!o.retryBefore().equals(o.firstDispatchedAt().plusSeconds(23*3600)))throw denied();
    }
    private static void requireAck(PreparationContext c,AdvanceAck ack,InvocationBudget b){
        if(ack==null||b==null||!ack.invocation().equals(b.invocation()))throw denied();identity(c,ack.operation());
        var old=c.admission().ready().observation();var post=ack.observation();
        if(!OwnedFailedGraceAdvance.sameClock(post,old)||!validPost(c,post)||!ack.acknowledgedAt().isBefore(micro(b.deadlineAt()))||ack.operation().firstDispatchedAt().isBefore(micro(b.enteredAt())))throw denied();
    }
    private void requirePost(PreparationContext c,ProviderOperation claim,TestClockAck post,Instant observedAt){
        if(post==null||observedAt==null||!OwnedFailedGraceAdvance.sameClock(post,c.admission().ready().observation())||!validPost(c,post)||observedAt.isBefore(claim.firstDispatchedAt())||observedAt.isAfter(utc.instant()))throw denied();
    }
    private static boolean validPost(PreparationContext c,TestClockAck post){
        long target=c.preparation().target(),previous=c.admission().ready().observation().frozenTime();
        return "ready".equals(post.status())?post.frozenTime()==target&&(post.advancingTarget()==null||post.advancingTarget()==target):"advancing".equals(post.status())&&post.frozenTime()>=previous&&post.frozenTime()<=target&&(post.advancingTarget()==null||post.advancingTarget()==target);
    }
    private void requireReady(PreparationContext c,AdvanceAck ack,TestClockReady ready,Instant observedAt){if(ready==null||observedAt==null||ready.frozenTime()!=c.preparation().target()||!OwnedFailedGraceAdvance.sameClock(ack.observation(),ready)||observedAt.isBefore(ack.acknowledgedAt())||observedAt.isAfter(utc.instant()))throw denied();}
    private static boolean sameOperation(ProviderOperation a,ProviderOperation b){return a.id().equals(b.id())&&a.accountId().equals(b.accountId())&&a.externalAccountId().equals(b.externalAccountId())&&a.credentialsRef().equals(b.credentialsRef())&&a.kind()==b.kind()&&a.resourceId().equals(b.resourceId())&&a.operationKey().equals(b.operationKey())&&a.fingerprint().equals(b.fingerprint())&&a.firstDispatchedAt().equals(b.firstDispatchedAt())&&a.retryBefore().equals(b.retryBefore());}
    private static Instant micro(Instant value){return value.truncatedTo(ChronoUnit.MICROS);}
    private void fresh(){if(transactions.active())throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("OWNED_FAILED_GRACE_ADVANCE_UNAVAILABLE");}
}
