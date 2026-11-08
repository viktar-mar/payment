package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Explicit inert selection only: real failed-renewal authority, transactional memory receipts. */
class OwnedFailedGraceAdvanceServiceTest {
    @Test void bothConsumedEntriesRefuseBeforeLifecycleWhileOriginalContinuationSkipsFreshEntry(){
        var f=new OwnedFailedGraceAdvanceInertFixture();var c=f.context;
        f.service.inspect(c.admission(),c.preparation(),f.budget);
        assertThat(f.trace()).containsSubsequence("begin","grace-limit","grace-fresh-entry","graceLocks","commit");
        f.trace().clear();var claim=f.claim();
        assertThat(f.trace()).containsSubsequence("begin","grace-limit","grace-fresh-entry","graceLocks","grace-firstclaim","commit");
        for(Runnable duplicate:List.<Runnable>of(()->f.service.inspect(c.admission(),c.preparation(),f.newBudget()),()->f.service.claim(c,"delayed-original",f.newBudget()))){
            f.trace().clear();assertThatThrownBy(duplicate::run).hasMessage("fresh grace entry refused");
            assertThat(f.trace()).containsExactly("begin","grace-limit","grace-fresh-entry","rollback");
        }
        f.trace().clear();f.service.inspectClaim(c,claim,f.budget);var ack=f.acknowledge(claim);
        f.service.inspectAck(c,ack,f.budget);f.service.recordReady(c,ack,f.ready(),f.now(),f.budget);
        assertThat(f.trace()).doesNotContain("grace-fresh-entry");assertThat(f.stops()).isZero();
    }
    @Test void firstClaimAckAndIndependentReadyUseOriginalSavedIdentityAndNeverFinancialAuthority(){
        var f=new OwnedFailedGraceAdvanceInertFixture();var before=f.financialState();var claim=f.claim();
        assertThat(claim.kind()).isEqualTo(Kind.OWNED_GRACE_CLOCK_ADVANCE);assertThat(claim.resourceId()).isEqualTo(f.context.preparation().id());
        assertThat(claim.operationKey()).isEqualTo(f.context.preparation().operationKey());assertThat(claim.fingerprint()).isEqualTo(f.context.preparation().fingerprint());
        assertThat(claim.status()).isEqualTo(Status.DISPATCHING);assertThat(claim.version()).isEqualTo(1);
        assertThat(claim.retryBefore()).isEqualTo(claim.firstDispatchedAt().plusSeconds(23*3600));assertThat(claim.leaseExpiresAt()).isEqualTo(claim.firstDispatchedAt().plusSeconds(60));
        assertThat(f.trace()).containsSubsequence("begin","grace-limit","graceLocks","operations","lifecycle","account","subscription","grace-firstclaim","commit");
        assertThat(f.service.inspectClaim(f.context,claim,f.budget)).isEqualTo(claim);
        var ack=f.acknowledge(claim);assertThat(ack.invocation()).isEqualTo(f.budget.invocation());assertThat(ack.deadline()).isEqualTo(claim.leaseExpiresAt());
        assertThat(f.service.inspectAck(f.context,ack,f.budget)).isEqualTo(ack);
        var ready=f.service.recordReady(f.context,ack,f.ready(),f.now(),f.budget);
        assertThat(ready.observation().frozenTime()).isEqualTo(f.context.failure().graceDeadline().getEpochSecond()-1);
        assertThat(f.store.claimTransaction).isLessThan(f.store.ackTransaction);assertThat(f.store.ackTransaction).isLessThan(f.transactionId);
        assertThat(f.savedReady()).isEqualTo(ready);assertThat(f.financialState()).isEqualTo(before);assertThat(f.stops()).isZero();
    }
    @Test void duplicateEveryStateAndAckReadyReplayNeverAdoptOrStop(){
        for(var status:Status.values()){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();f.store.row=OwnedFailedGraceAdvanceInertFixture.copy(claim,status,status==Status.RESERVED?0:2,null,null,null);
            var before=f.store.snapshot();assertThatThrownBy(f::claim).as(status.name()).isInstanceOf(RuntimeException.class);assertThat(f.store.snapshot()).isEqualTo(before);assertThat(f.stops()).isZero();
        }
        var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();var ack=f.acknowledge(claim);
        assertThatThrownBy(()->f.acknowledge(claim)).isInstanceOf(RuntimeException.class);
        f.service.recordReady(f.context,ack,f.ready(),f.now(),f.budget);var saved=f.store.snapshot();
        assertThatThrownBy(()->f.service.recordReady(f.context,ack,f.ready(),f.now(),f.budget)).isInstanceOf(RuntimeException.class);assertThat(f.store.snapshot()).isEqualTo(saved);
    }
    @Test void ambientTransactionIsDeniedBeforeStoreOrCurrentAuthentication(){
        var f=new OwnedFailedGraceAdvanceInertFixture();f.h.active=true;
        try{assertThatThrownBy(f::claim).isInstanceOf(IllegalStateException.class);assertThat(f.store.calls).isEmpty();assertThat(f.trace()).isEmpty();}finally{f.h.active=false;}
    }
    @Test void changedPersistedPreparationOrDebtSampleAndSourceCannotReachReceiptWriter(){
        for(var drift:List.of("sample","mode","owner","debt","preparation","stop","recovery")){
            var f=new OwnedFailedGraceAdvanceInertFixture();var before=f.financialState();
            switch(drift){case "sample"->f.h.billingSecond++;case "mode"->f.h.isolated=false;case "owner"->f.h.foreign=true;case "debt"->f.h.sequence++;case "preparation"->f.h.preparation=null;case "stop"->f.h.stopped=true;default->f.h.service.reserveExistingRecovery(f.h.admission);}
            f.store.calls.clear();assertThatThrownBy(f::claim).as(drift).isInstanceOf(RuntimeException.class);assertThat(f.store.calls).containsExactly("limit");assertThat(f.row()).isNull();
            if(!drift.equals("recovery")&&!drift.equals("sample")&&!drift.equals("debt")&&!drift.equals("preparation"))assertThat(f.financialState()).isEqualTo(before);
        }
    }
    @Test void foreignOrReplacedInvocationClaimantAndDescriptionRefuseBeforeReceiptMutation(){
        var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();var before=f.store.snapshot();
        assertThatThrownBy(()->f.service.inspectClaim(f.context,claim,f.newBudget())).isInstanceOf(RuntimeException.class);
        var forged=OwnedFailedGraceAdvanceInertFixture.copy(claim,claim.status(),claim.version(),null,"other-claimant",claim.leaseExpiresAt());
        assertThatThrownBy(()->f.service.inspectClaim(f.context,forged,f.budget)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->f.service.claim(f.context," ",f.budget)).isInstanceOf(RuntimeException.class);
        assertThat(f.store.snapshot()).isEqualTo(before);assertThat(f.stops()).isZero();
    }
    @Test void independentlyCommittedClaimAndAckAreRequiredEvenWithExactDescriptions(){
        var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();f.store.committedTransaction=0;
        assertThatThrownBy(()->f.service.inspectClaim(f.context,claim,f.budget)).isInstanceOf(RuntimeException.class);
        f.store.committedTransaction=f.store.claimTransaction;var ack=f.acknowledge(claim);f.store.committedTransaction=f.store.claimTransaction;
        assertThatThrownBy(()->f.service.inspectAck(f.context,ack,f.budget)).isInstanceOf(RuntimeException.class);assertThat(f.savedReady()).isNull();
    }
    @Test void rollbackAtReceiptAndFinalPrecommitExpiryPreservesPreviousDurableState(){
        for(var phase:List.of("claim","ack","ready"))for(var boundary:List.of("lock","effect")){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=phase.equals("claim")?null:f.claim();var ack=phase.equals("ready")?f.acknowledge(claim):null;
            var receipt=f.store.snapshot();var financial=f.financialState();Runnable expire=()->f.nanos=30_000_000_000L;
            if(boundary.equals("effect"))f.store.afterEffect=expire;else f.store.afterLock=expire;
            assertThatThrownBy(()->{switch(phase){case "claim"->f.claim();case "ack"->f.acknowledge(claim);default->f.service.recordReady(f.context,ack,f.ready(),f.now(),f.budget);}}).as(phase+"/"+boundary).isInstanceOf(RuntimeException.class);
            assertThat(f.store.snapshot()).isEqualTo(receipt);assertThat(f.financialState()).isEqualTo(financial);assertThat(f.trace()).contains("rollback");
        }
    }
    @Test void driftAfterPositiveWriteAndAppendFailureRollBackWithoutImplicitStop(){
        for(var phase:List.of("claim","ack","ready")){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=phase.equals("claim")?null:f.claim();var ack=phase.equals("ready")?f.acknowledge(claim):null;var old=f.store.snapshot();
            f.store.afterEffect=()->f.h.billingSecond++;
            assertThatThrownBy(()->{switch(phase){case "claim"->f.claim();case "ack"->f.acknowledge(claim);default->f.service.recordReady(f.context,ack,f.ready(),f.now(),f.budget);}}).isInstanceOf(RuntimeException.class);
            assertThat(f.store.snapshot()).isEqualTo(old);assertThat(f.stops()).isZero();
        }
        var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();f.store.failAck=true;
        assertThatThrownBy(()->f.acknowledge(claim)).hasMessage("append ack failed");assertThat(f.row()).isEqualTo(claim);assertThat(f.ack()).isNull();
    }
    @Test void originalClocksOnlyConsumeTimeAndInterruptSurvivesRefusal(){
        for(var bad:List.of("utc","monotonic","interrupt")){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();
            if(bad.equals("utc"))f.at(f.now().minusNanos(1));else if(bad.equals("monotonic")){f.nanos=5;f.service.inspectClaim(f.context,claim,f.budget);f.nanos=4;}else Thread.currentThread().interrupt();
            try{assertThatThrownBy(()->f.service.inspectClaim(f.context,claim,f.budget)).as(bad).isInstanceOf(RuntimeException.class);if(bad.equals("interrupt"))assertThat(Thread.currentThread().isInterrupted()).isTrue();}finally{Thread.interrupted();}
            assertThat(f.row()).isEqualTo(claim);assertThat(f.stops()).isZero();
        }
    }
    @Test void exactOwnerCleanupWorksAfterExpiryAndDriftButBorrowedInvocationCannotStopWinner(){
        var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();var old=f.store.snapshot();
        f.service.uncertain(f.context,claim,null,f.newBudget());assertThat(f.store.snapshot()).isEqualTo(old);
        f.nanos=31_000_000_000L;f.at(f.budget.deadlineAt().plusSeconds(1));f.h.foreign=true;
        f.service.uncertain(f.context,claim,null,f.budget);assertThat(f.row().status()).isEqualTo(Status.UNKNOWN);assertThat(f.stops()).isEqualTo(1);
        f.service.uncertain(f.context,claim,null,f.budget);assertThat(f.stops()).isEqualTo(1);
    }
    @Test void cleanupPreservesAckAndTerminalReady(){
        for(boolean ready:List.of(false,true)){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();var ack=f.acknowledge(claim);if(ready)f.service.recordReady(f.context,ack,f.ready(),f.now(),f.budget);
            var saved=f.savedReady();f.nanos=31_000_000_000L;f.h.foreign=true;f.service.uncertain(f.context,claim,ack,f.budget);
            assertThat(f.ack()).isEqualTo(ack);assertThat(f.row()).isEqualTo(ack.operation());assertThat(f.savedReady()).isEqualTo(saved);assertThat(f.stops()).isEqualTo(ready?0:1);
        }
    }
    @Test void delayedCommitReturnCannotEraseAlreadyCommittedAckOrReady(){
        for(var phase:List.of("ack","ready")){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();var originalAck=phase.equals("ready")?f.acknowledge(claim):null;
            f.afterCommit=()->{f.nanos=31_000_000_000L;f.at(f.budget.deadlineAt().plusSeconds(1));};
            if(phase.equals("ack"))assertThat(f.acknowledge(claim)).isEqualTo(f.ack());
            else assertThat(f.service.recordReady(f.context,originalAck,f.ready(),f.now(),f.budget)).isEqualTo(f.savedReady());
            assertThat(f.row().status()).isEqualTo(Status.ACKNOWLEDGED);assertThat(f.ack()).isNotNull();
        }
    }
    @Test void throwingCommitReturnPreservesDurableAckAndReadyWithoutPositiveAdoption(){
        for(var phase:List.of("ack","ready")){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();var originalAck=phase.equals("ready")?f.acknowledge(claim):null;
            f.afterCommit=()->{throw new IllegalStateException("synthetic ambiguous committed return");};
            assertThatThrownBy(()->{if(originalAck==null)f.acknowledge(claim);else f.service.recordReady(f.context,originalAck,f.ready(),f.now(),f.budget);}).hasMessage("synthetic ambiguous committed return");
            f.afterCommit=()->{};var committed=f.store.snapshot();var committedAck=f.ack();var financial=f.financialState();assertThat(committedAck).isNotNull();assertThat(f.row().status()).isEqualTo(Status.ACKNOWLEDGED);
            // Exact readable claim without the original invocation remains insufficient, even after ambiguous return.
            f.service.uncertain(f.context,claim,null,f.newBudget());assertThat(f.store.snapshot()).isEqualTo(committed);
            f.trace().clear();f.store.calls.clear();f.nanos=31_000_000_000L;f.h.foreign=true;
            // A missing return envelope cannot strand the original owner. Cleanup authenticates saved ACK identity only.
            f.service.uncertain(f.context,claim,null,f.budget);
            assertThat(f.ack()).isEqualTo(committedAck);assertThat(f.row()).isEqualTo(committedAck.operation());assertThat(f.financialState()).isEqualTo(financial);
            assertThat(f.trace()).containsExactly("begin","grace-cleanup","commit");assertThat(f.store.calls).containsExactly("cleanup");
            assertThat(f.stops()).isEqualTo(phase.equals("ack")?1:0);
            assertThatThrownBy(f::claim).isInstanceOf(RuntimeException.class);if(phase.equals("ready")){assertThat(f.savedReady()).isNotNull();assertThat(f.store.snapshot()).isEqualTo(committed);}else assertThat(f.savedReady()).isNull();
        }
    }
    @Test void originalCleanupSurvivesApplicationUtcRollbackBeforeClaimOrCommittedAck(){
        for(boolean acknowledged:List.of(false,true)){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();var ack=acknowledged?f.acknowledge(claim):null;var financial=f.financialState();
            f.at(claim.firstDispatchedAt().minusSeconds(1));f.nanos=31_000_000_000L;f.h.foreign=true;f.trace().clear();f.store.calls.clear();
            f.service.uncertain(f.context,claim,ack,f.budget);
            assertThat(f.row().status()).isEqualTo(acknowledged?Status.ACKNOWLEDGED:Status.UNKNOWN);assertThat(f.ack()).isEqualTo(ack);assertThat(f.stops()).isEqualTo(1);
            assertThat(f.trace()).containsExactly("begin","grace-cleanup","commit");assertThat(f.store.calls).containsExactly("cleanup");assertThat(f.financialState()).isEqualTo(financial);
        }
    }
    @Test void rawNanosecondsAreNotRoundedIntoRenewedInvocationIdentity(){
        var f=new OwnedFailedGraceAdvanceInertFixture(123456789);var budget=f.budget;var claim=f.claim();
        // The raw object survives receipt projection; a truncated receipt never replaces the budget.
        assertThat(budget.enteredAt().getNano()).isEqualTo(123456789);assertThat(budget.deadlineAt()).isEqualTo(budget.enteredAt().plusSeconds(30));
        assertThat(claim.firstDispatchedAt().getNano()).isEqualTo(123456000);assertThat(f.store.original).isSameAs(budget);
        assertThat(f.service.inspectClaim(f.context,claim,budget)).isEqualTo(claim);assertThat(f.acknowledge(claim).acknowledgedAt().getNano()).isEqualTo(123456000);
        var g=new OwnedFailedGraceAdvanceInertFixture();var c=g.claim();g.at(g.budget.deadlineAt().minusNanos(1));
        g.store.afterEffect=()->g.at(g.budget.deadlineAt());assertThatThrownBy(()->g.acknowledge(c)).isInstanceOf(RuntimeException.class);assertThat(g.ack()).isNull();
    }
    @Test void callerBudgetLimiterPrecedesAllCurrentProofIncludingInspectionAndRefusesWithoutAuthorityWork(){
        for(var phase:List.of("inspect","claim","inspectClaim","ack","inspectAck","ready")){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=List.of("inspect","claim").contains(phase)?null:f.claim();var ack=List.of("inspectAck","ready").contains(phase)?f.acknowledge(claim):null;
            f.trace().clear();f.store.calls.clear();f.store.failLimiter=true;var receipt=f.store.snapshot();
            assertThatThrownBy(()->{switch(phase){
                case "inspect"->f.service.inspect(f.context.admission(),f.context.preparation(),f.budget);case "claim"->f.claim();case "inspectClaim"->f.service.inspectClaim(f.context,claim,f.budget);case "ack"->f.acknowledge(claim);case "inspectAck"->f.service.inspectAck(f.context,ack,f.budget);default->f.service.recordReady(f.context,ack,f.ready(),f.now(),f.budget);
            }}).as(phase).hasMessage("caller limiter unavailable");
            assertThat(f.trace()).containsExactly("begin","grace-limit","rollback");assertThat(f.store.calls).containsExactly("limit");assertThat(f.store.snapshot()).isEqualTo(receipt);
        }
    }
    @Test void malformedReturnedClaimCannotEscapeAuthenticatedBoundary(){
        for(var wrong:List.of("resource","key","fingerprint","account","claimant")){
            var f=new OwnedFailedGraceAdvanceInertFixture();f.store.afterEffect=()->{var o=f.store.row;f.store.row=new ProviderOperation(o.id(),wrong.equals("account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(UUID.randomUUID()):o.accountId(),o.externalAccountId(),o.credentialsRef(),o.kind(),wrong.equals("resource")?UUID.randomUUID():o.resourceId(),wrong.equals("key")?o.operationKey()+":retry":o.operationKey(),wrong.equals("fingerprint")?"a".repeat(64):o.fingerprint(),o.status(),null,o.firstDispatchedAt(),o.retryBefore(),wrong.equals("claimant")?"foreign":o.leaseOwner(),o.leaseExpiresAt(),o.version());};
            assertThatThrownBy(f::claim).as(wrong).isInstanceOf(RuntimeException.class);assertThat(f.row()).isNull();assertThat(f.stops()).isZero();
        }
    }
}
