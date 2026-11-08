package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.CustomerSessionId;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.RecoveryPreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.model.ProviderRequestFingerprint;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedRecoveryConfirmStore;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Real original-method authentication and lifecycle, with rollback-aware inert receipt storage only.
 * No Spring context, SQL engine, file, SDK, socket, scheduler, or financial producer is reachable. */
class OwnedRecoveryConfirmServiceTest {
    @Test void firstClaimCommitsOnceAndExactAckDoesNotPublishOrSettleAnything(){
        var h=new Harness();var before=h.f.state();var claim=h.claim();
        assertThat(claim.kind()).isEqualTo(Kind.OWNED_RECOVERY_INTENT_CONFIRM);
        assertThat(claim.resourceId()).isEqualTo(h.expected.recoveryOperation().id());
        assertThat(claim.operationKey()).isEqualTo(h.expected.recoveryOperation().providerOperationKey()+":CONFIRM");
        assertThat(claim.status()).isEqualTo(Status.DISPATCHING);assertThat(claim.version()).isEqualTo(1);
        assertThat(claim.leaseExpiresAt()).isEqualTo(claim.firstDispatchedAt().plusSeconds(60));
        assertThat(h.store.creatingTransaction).isPositive();assertThat(h.store.committed).isTrue();
        assertThat(h.store.invocation).isEqualTo(h.budget.invocation());
        assertThat(h.store.entered).isEqualTo(h.budget.enteredAt());assertThat(h.store.deadline).isEqualTo(h.budget.deadlineAt());
        assertThat(h.service.inspectClaim(h.expected,claim,h.f.command(),h.budget)).isEqualTo(claim);
        var reply=h.reply();var ack=h.service.acknowledge(h.expected,claim,reply,h.f.command(),h.budget);
        assertThat(ack.operation().id()).isEqualTo(claim.id());assertThat(ack.operation().version()).isEqualTo(2);
        assertThat(ack.operation().status()).isEqualTo(Status.ACKNOWLEDGED);assertThat(ack.reply()).isEqualTo(reply);
        assertThat(ack.operation().providerReference()).isEqualTo(h.expected.reservation().failure().paymentIntent());
        assertThat(h.store.acks).containsExactly(ack);assertThat(h.f.state()).isEqualTo(before);
        assertThat(h.f.h.b.trace.stream().filter("begin"::equals).count()).isEqualTo(3);
        assertThat(h.store.budgets).allSatisfy(b->assertThat(b).isSameAs(h.budget));
    }

    @Test void authenticatedOperationsPrecedeReceiptAndOriginalBrowserChecksBracketEachEffect(){
        var h=new Harness();h.claim();var trace=h.f.trace();
        assertThat(trace).containsSubsequence("begin","recovery-identity","operations","confirm-lock","recovery-enrollment","confirm-insert","recovery-enrollment","commit");
        var canonical=new ArrayList<>(List.of(h.expected.reservation().admission().firstRenewalAdmission().receipt().intent().createOperation(),h.expected.setupOperation().id(),h.expected.recoveryOperation().id()));
        canonical.sort(Comparator.naturalOrder());
        var observed=h.f.h.b.operationLockTargets;
        assertThat(observed).hasSize(2*canonical.size());
        assertThat(observed.subList(0,canonical.size())).containsExactlyElementsOf(canonical).isSorted();
        assertThat(observed.subList(canonical.size(),observed.size())).containsExactlyElementsOf(canonical).isSorted();
        var positions=new ArrayList<Integer>();for(int i=0;i<trace.size();i++)if(trace.get(i).equals("operations"))positions.add(i);
        assertThat(positions).hasSize(6);
        positions.subList(0,3).forEach(index->assertThat(index).isLessThan(trace.indexOf("confirm-lock")));
        assertThat(trace.indexOf("confirm-lock")).isLessThan(trace.indexOf("recovery-enrollment"));
        positions.subList(3,6).forEach(index->{assertThat(index).isGreaterThan(trace.indexOf("confirm-insert"));assertThat(index).isLessThan(trace.lastIndexOf("recovery-enrollment"));});
        var claim=h.store.row;h.f.trace().clear();h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget);
        assertThat(h.f.trace()).containsSubsequence("begin","operations","confirm-lock","recovery-enrollment","confirm-ack","recovery-enrollment","commit");
    }

    @Test void allExistingStatesRefuseAdoptionAndNeverReacquire(){
        for(var state:Status.values()){
            var h=new Harness();var claim=h.claim();
            // Negative existing receipts only: never used as success evidence.
            h.store.row=h.store.copy(claim,state,state==Status.RESERVED?0:2);
            var old=h.store.row;int writes=h.store.writes;
            assertThatThrownBy(h::claim).as(state.name()).isInstanceOf(RuntimeException.class);
            assertThat(h.store.row).isEqualTo(old);assertThat(h.store.writes).isEqualTo(writes);
        }
    }

    @Test void foreignContextOrForgedDescriptionCannotSelectAReceiptLock(){
        for(var mutation:List.of("session","owner","description","claim")){
            var h=new Harness();var claim=h.claim();h.store.calls.clear();h.f.trace().clear();h.f.h.b.operationLockTargets.clear();
            var c=h.f.command();var expected=h.expected;
            if(mutation.equals("session")){
                var s=((SubscriptionActor.Customer)c.actor()).session();
                var other=new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),s.siteId(),s.customerId(),s.origin(),s.scopes(),s.expiresAt());
                c=new SubscriptionCommandContext(new SubscriptionActor.Customer(other),c.siteId(),c.customerId(),c.origin(),c.idempotencyKey(),"foreign");
            }
            if(mutation.equals("owner"))c=new OwnedFailedRenewalMethodServiceTest.Harness().context;
            if(mutation.equals("description")){
                var foreign=new Harness().expected;
                expected=new RecoveryPreparationContext(expected.reservation(),expected.enrollment(),expected.account(),expected.subscription(),expected.setupOperation(),foreign.recoveryOperation(),expected.binding(),expected.completion(),expected.recovery(),expected.billingSample());
            }
            if(mutation.equals("claim"))claim=new ProviderOperation(UUID.randomUUID(),claim.accountId(),claim.externalAccountId(),claim.credentialsRef(),claim.kind(),UUID.randomUUID(),claim.operationKey(),claim.fingerprint(),claim.status(),null,claim.firstDispatchedAt(),claim.retryBefore(),claim.leaseOwner(),claim.leaseExpiresAt(),claim.version());
            var command=c;var description=expected;var supplied=claim;var before=h.f.state();
            assertThatThrownBy(()->h.service.inspectClaim(description,supplied,command,h.budget)).as(mutation).isInstanceOf(RuntimeException.class);
            assertThat(h.store.calls).doesNotContain("require");
            if(!mutation.equals("claim"))assertThat(h.store.calls).isEmpty();
            assertThat(h.f.state()).isEqualTo(before);
        }
    }

    @Test void rollbackAfterReceiptLockMutationAndFinalAuthorityWaitPreservesOriginalState(){
        for(var effect:List.of("claim","ack"))for(var boundary:List.of("lock","effect","authority"))for(var drift:List.of("raw-expiry","revocation","budget","sequence")){
            var h=new Harness();ProviderOperation claim=effect.equals("ack")?h.claim():null;
            var row=h.store.row;var before=h.f.state();var receipts=new ArrayList<>(h.store.acks);var receiptBefore=h.receiptState();
            h.store.effectReached=false;
            Runnable change=()->{switch(drift){
                case "raw-expiry"->h.f.at(((SubscriptionActor.Customer)h.f.command().actor()).session().expiresAt());
                case "revocation"->h.f.h.liveSession=false;
                case "budget"->h.nanos=30_000_000_000L;
                default->h.f.driftSequence();
            }};
            if(boundary.equals("lock"))h.store.afterLock=change;
            if(boundary.equals("effect"))h.store.afterEffect=change;
            if(boundary.equals("authority"))h.f.h.afterAuthority=()->{if(h.store.effectReached)change.run();};
            assertThatThrownBy(()->{if(claim==null)h.claim();else h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget);})
                .as(effect+"/"+boundary+"/"+drift).isInstanceOf(RuntimeException.class);
            assertThat(h.store.row).isEqualTo(row);assertThat(h.store.acks).isEqualTo(receipts);
            assertThat(h.receiptState()).isEqualTo(receiptBefore);
            // Clock/session/revocation inputs are external; transactional financial/lifecycle state rolls back.
            assertThat(h.f.state()).isEqualTo(before);assertThat(h.f.trace()).contains("rollback");
        }
    }

    @Test void rawExclusiveSessionExpiryCannotBeHiddenByMicrosecondTruncation(){
        var h=new Harness();var session=((SubscriptionActor.Customer)h.f.command().actor()).session();
        h.f.at(session.expiresAt().minusNanos(1));var fresh=h.newBudget();
        h.store.afterEffect=()->h.f.at(session.expiresAt());
        assertThatThrownBy(()->h.service.claim(h.expected,h.f.command(),"first",fresh)).isInstanceOf(RuntimeException.class);
        assertThat(h.store.row).isNull();assertThat(h.store.acks).isEmpty();
    }

    @Test void expiryOnlyDuringTheFinalNegativeGateRollsBackWhileInvocationAndLeaseRemainLive(){
        for(var phase:List.of("claim","inspect","ack")){
            var h=new Harness();var session=((SubscriptionActor.Customer)h.f.command().actor()).session();
            h.f.at(session.expiresAt().minusSeconds(10));var budget=new InvocationBudget(h.f.clock(),()->h.nanos);
            var claim=phase.equals("claim")?null:h.service.claim(h.expected,h.f.command(),"near-expiry-owner",budget);
            var before=h.f.state();var receiptBefore=h.receiptState();var gates=new java.util.concurrent.atomic.AtomicInteger();
            h.f.h.afterUnobservedRecovery=()->{if(gates.incrementAndGet()==3){
                h.f.at(session.expiresAt());assertThat(h.f.now()).isBefore(budget.deadlineAt());
                var liveClaim=claim==null?h.store.row:claim;assertThat(budget.remainingNanos(liveClaim)).isPositive();
                assertThat(h.f.now()).isBefore(liveClaim.leaseExpiresAt());
            }};
            assertThatThrownBy(()->{switch(phase){
                case "claim"->h.service.claim(h.expected,h.f.command(),"near-expiry-owner",budget);
                case "inspect"->h.service.inspectClaim(h.expected,claim,h.f.command(),budget);
                default->h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),budget);
            }}).as(phase).isInstanceOf(RuntimeException.class);
            assertThat(gates).hasValue(3);assertThat(h.f.state()).isEqualTo(before);assertThat(h.receiptState()).isEqualTo(receiptBefore);
            assertThat(h.f.trace()).contains("rollback");
        }
    }

    @Test void sameInvocationBudgetCannotBeReplacedResetOrExtendedAfterClaim(){
        var h=new Harness();var claim=h.claim();
        assertThatThrownBy(()->h.service.inspectClaim(h.expected,claim,h.f.command(),h.newBudget())).isInstanceOf(RuntimeException.class);
        h.nanos=30_000_000_000L;
        assertThatThrownBy(()->h.service.inspectClaim(h.expected,claim,h.f.command(),h.budget)).isInstanceOf(RuntimeException.class);
        assertThat(h.store.row).isEqualTo(claim);assertThat(h.store.acks).isEmpty();
        var rollback=new Harness();var original=rollback.f.now();rollback.f.at(original.minusNanos(1));
        assertThatThrownBy(rollback::claim).isInstanceOf(RuntimeException.class);assertThat(rollback.store.row).isNull();
    }

    @Test void rollbackAfterAnObservedCheckpointCannotRestoreConsumedBudget(){
        for(var source:List.of("monotonic","utc")){
            var h=new Harness();var claim=h.claim();var start=h.f.now();
            if(source.equals("monotonic"))h.nanos=5_000_000_000L;else h.f.at(start.plusSeconds(5));
            assertThat(h.service.inspectClaim(h.expected,claim,h.f.command(),h.budget)).isEqualTo(claim);h.store.calls.clear();
            if(source.equals("monotonic"))h.nanos=4_000_000_000L;else h.f.at(start.plusSeconds(4));
            assertThatThrownBy(()->h.service.inspectClaim(h.expected,claim,h.f.command(),h.budget)).isInstanceOf(RuntimeException.class);
            assertThat(h.store.calls).isEmpty();assertThat(h.store.row).isEqualTo(claim);assertThat(h.store.acks).isEmpty();
        }
    }

    @Test void priorIndependentRecoveredReadPermanentlyRefusesFinancialAdmission(){
        var h=new Harness();h.f.service().beginRecoveredRead(h.f.reservation(),h.f.recovery(),UUID.randomUUID());
        // Descriptive public reads retain their original semantics; only the financial seam denies.
        assertThat(h.f.inspect()).isEqualTo(h.expected);h.f.trace().clear();
        var before=h.f.state();assertThatThrownBy(h::claim).isInstanceOf(RuntimeException.class);
        assertThat(h.store.row).isNull();assertThat(h.f.state()).isEqualTo(before);
        assertThat(h.f.trace()).containsSubsequence("operations","recovery-unobserved");
    }

    @Test void sharedCanonicalEncodingKeepsLiteralUtf16LengthsNullsAndStrictUtf8Digest() throws Exception {
        var account=PaymentAccount.create(new PaymentAccountId(UUID.fromString("00000000-0000-0000-0000-000000000001")),"inert",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_inert","alias");
        String literal="36:00000000-0000-0000-0000-00000000000110:acct_inert5:alias6:STRIPE7:SANDBOX3:A😀-1:";
        var observed=new ArrayList<String>();
        var actual=ProviderRequestFingerprint.of(value->{observed.add(value);try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}},account,"A😀",null);
        var expected=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(literal.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(observed).containsExactly(literal);assertThat(actual).isEqualTo(expected);
    }

    @Test void receiptMustHaveCommittedInAnotherTransactionEvenIfAnInMemoryClaimLooksLive(){
        var h=new Harness();var claim=h.claim();h.store.committed=false;
        assertThatThrownBy(()->h.service.inspectClaim(h.expected,claim,h.f.command(),h.budget)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget)).isInstanceOf(RuntimeException.class);
        assertThat(h.store.row).isEqualTo(claim);assertThat(h.store.acks).isEmpty();
    }

    @Test void replyMustMatchOriginalIntentCustomerMethodMoneyAndObservationTime(){
        for(var bad:List.of("intent","customer","method","currency","amount","received","capturable","future","before-claim")){
            var h=new Harness();var claim=h.claim();var r=h.reply();var before=h.f.state();
            assertThatThrownBy(()->h.service.acknowledge(h.expected,claim,new ConfirmReply(
                bad.equals("intent")?"pi_foreign":r.paymentIntent(),bad.equals("customer")?"cus_foreign":r.customer(),
                bad.equals("method")?"pm_foreign":r.method(),bad.equals("currency")?"USD":r.currency(),
                bad.equals("amount")?r.amount()+1:r.amount(),bad.equals("received")?r.received()-1:r.received(),
                bad.equals("capturable")?1:0,r.charge(),bad.equals("future")?h.f.now().plusNanos(1):bad.equals("before-claim")?claim.firstDispatchedAt().minusNanos(1):r.observedAt()),h.f.command(),h.budget))
                .as(bad).isInstanceOf(RuntimeException.class);
            assertThat(h.store.row).isEqualTo(claim);assertThat(h.store.acks).isEmpty();assertThat(h.f.state()).isEqualTo(before);
        }
    }

    @Test void exactAckRollsBackWhenAppendOnlyReceiptWriteFailsAndCannotBeReplayed(){
        var h=new Harness();var claim=h.claim();h.store.failAck=true;
        assertThatThrownBy(()->h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget)).isInstanceOf(RuntimeException.class);
        assertThat(h.store.row).isEqualTo(claim);assertThat(h.store.acks).isEmpty();h.store.failAck=false;
        h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget);var ack=h.store.acks.getFirst();
        assertThatThrownBy(()->h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget)).isInstanceOf(RuntimeException.class);
        assertThat(h.store.acks).containsExactly(ack);
    }

    @Test void ackCommittedBeforeDeadlineIsReturnedEvenWhenCommitReturnCrossesDeadline(){
        var h=new Harness();var claim=h.claim();h.afterCommit=()->{h.nanos=31_000_000_000L;h.f.at(h.budget.deadlineAt().plusSeconds(1));};
        var ack=h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget);
        assertThat(h.store.acks).containsExactly(ack);assertThat(ack.operation().status()).isEqualTo(Status.ACKNOWLEDGED);
    }

    @Test void anotherInvocationWithExactReadbackCannotConsumeOrStopTheOriginalClaim(){
        var h=new Harness();var owner=h.claim();var independentlyInspected=h.f.inspect();var secondInvocation=h.newBudget();
        assertThat(independentlyInspected).isEqualTo(h.expected);
        assertThat(secondInvocation.invocation()).isNotEqualTo(h.budget.invocation());
        assertThat(secondInvocation.enteredAt()).isEqualTo(h.budget.enteredAt());assertThat(secondInvocation.deadlineAt()).isEqualTo(h.budget.deadlineAt());
        // Every claim field is readable; possession of an equal copy must not confer cleanup ownership.
        var readback=h.store.row;
        var borrowed=new ProviderOperation(readback.id(),readback.accountId(),readback.externalAccountId(),readback.credentialsRef(),readback.kind(),readback.resourceId(),readback.operationKey(),readback.fingerprint(),readback.status(),readback.providerReference(),readback.firstDispatchedAt(),readback.retryBefore(),readback.leaseOwner(),readback.leaseExpiresAt(),readback.version());
        assertThat(borrowed).isEqualTo(owner).isNotSameAs(owner);
        var receipts=h.receiptState();var before=h.f.state();int writes=h.store.writes;
        h.service.uncertain(independentlyInspected,borrowed,secondInvocation);
        assertThat(h.receiptState()).isEqualTo(receipts);assertThat(h.store.writes).isEqualTo(writes);
        assertThat(h.f.state()).isEqualTo(before);assertThat(h.f.h.b.stopped).isFalse();
        h.f.at(((SubscriptionActor.Customer)h.f.command().actor()).session().expiresAt().plusSeconds(1));h.nanos=31_000_000_000L;h.f.h.liveSession=false;
        h.service.uncertain(independentlyInspected,borrowed,h.budget);
        assertThat(h.store.row.status()).isEqualTo(Status.UNKNOWN);assertThat(h.store.row.version()).isEqualTo(2);
        assertThat(h.store.acks).isEmpty();assertThat(h.f.h.b.stopped).isTrue();
        assertThat(h.f.h.b.s).isEqualTo(((List<?>)before).getFirst());
    }

    @Test void cleanupRefusesNullProofOrMissingMismatchedOrUncommittedInvocationReceipt(){
        for(var bad:List.of("null-proof","missing-receipt","uncommitted","same-transaction","entry","deadline")){
            var h=new Harness();var claim=h.claim();
            switch(bad){
                case "missing-receipt"->{h.store.invocation=null;h.store.entered=null;h.store.deadline=null;}
                case "uncommitted"->h.store.committed=false;
                case "same-transaction"->h.store.creatingTransaction=h.transaction+1;
                case "entry"->h.store.entered=h.store.entered.plusNanos(1);
                case "deadline"->h.store.deadline=h.store.deadline.plusNanos(1);
                default->{}
            }
            var receipts=h.receiptState();var before=h.f.state();int writes=h.store.writes;
            h.service.uncertain(h.expected,claim,bad.equals("null-proof")?null:h.budget);
            assertThat(h.receiptState()).as(bad).isEqualTo(receipts);assertThat(h.store.writes).as(bad).isEqualTo(writes);
            assertThat(h.f.state()).as(bad).isEqualTo(before);assertThat(h.f.h.b.stopped).as(bad).isFalse();
        }
    }

    @Test void cleanupUsesIdentityWithoutSamplingBudgetOrCheckingInterruption(){
        var h=new Harness();var claim=h.claim();
        // A rollback would invalidate a positive budget checkpoint, but cannot revoke cleanup identity.
        h.f.at(h.budget.enteredAt().minusSeconds(1));h.nanos=-1;
        boolean previous=Thread.interrupted();
        try{
            Thread.currentThread().interrupt();h.service.uncertain(h.expected,claim,h.budget);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(h.store.row.status()).isEqualTo(Status.UNKNOWN);
            assertThat(h.f.h.b.stopped).isTrue();assertThat(h.store.acks).isEmpty();
        }finally{Thread.interrupted();if(previous)Thread.currentThread().interrupt();}
    }

    @Test void uncertainConsumesOnlyOwnCommittedClaimEvenAfterExpiryWithoutFinancialAuthority(){
        var h=new Harness();var claim=h.claim();h.f.at(((SubscriptionActor.Customer)h.f.command().actor()).session().expiresAt().plusSeconds(1));h.nanos=31_000_000_000L;h.f.h.liveSession=false;
        var before=h.f.state();h.service.uncertain(h.expected,claim,h.budget);
        assertThat(h.store.row.status()).isEqualTo(Status.UNKNOWN);assertThat(h.store.row.version()).isEqualTo(2);
        assertThat(h.store.acks).isEmpty();assertThat(h.f.h.b.stopped).isTrue();
        assertThat(h.f.h.b.s).isEqualTo(((List<?>)before).getFirst());
        var stopped=h.f.state();var receipts=h.receiptState();int writes=h.store.writes;
        h.service.uncertain(h.expected,claim,h.budget);assertThat(h.f.state()).isEqualTo(stopped);assertThat(h.receiptState()).isEqualTo(receipts);assertThat(h.store.writes).isEqualTo(writes);
        assertThatThrownBy(h::claim).isInstanceOf(RuntimeException.class);
    }

    @Test void foreignOrAcknowledgedClaimCannotConsumeOrStopTheOriginalAdmission(){
        for(boolean acknowledged:List.of(false,true)){
            var h=new Harness();var claim=h.claim();if(acknowledged)h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget);
            var row=h.store.row;var receipts=h.receiptState();int writes=h.store.writes;var before=h.f.state();var foreign=new ProviderOperation(UUID.randomUUID(),claim.accountId(),claim.externalAccountId(),claim.credentialsRef(),claim.kind(),claim.resourceId(),claim.operationKey(),claim.fingerprint(),claim.status(),null,claim.firstDispatchedAt(),claim.retryBefore(),"foreign",claim.leaseExpiresAt(),claim.version());
            h.service.uncertain(h.expected,acknowledged?claim:foreign,h.budget);
            assertThat(h.store.row).isEqualTo(row);assertThat(h.receiptState()).isEqualTo(receipts);assertThat(h.store.writes).isEqualTo(writes);
            assertThat(h.f.state()).isEqualTo(before);assertThat(h.f.h.b.stopped).isFalse();
        }
    }

    @Test void changedAuthorityAndDebtOnlyAllowImmutableCleanupOfTheExactOwnClaim(){
        var h=new Harness();var claim=h.claim();h.f.revoke();h.f.driftSequence();
        var s=h.f.h.b.s;h.f.h.b.s=new dev.genericpayments.platform.subscriptions.domain.Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),null,s.terminalReason(),s.revision()+1,s.createdAt(),s.updatedAt(),s.lastReconciledAt());
        var before=h.f.state();assertThatThrownBy(()->h.service.inspectClaim(h.expected,claim,h.f.command(),h.budget)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget)).isInstanceOf(RuntimeException.class);
        h.f.at(((SubscriptionActor.Customer)h.f.command().actor()).session().expiresAt().plusSeconds(1));h.nanos=31_000_000_000L;h.f.h.liveSession=false;
        h.service.uncertain(h.expected,claim,h.budget);assertThat(h.store.row.status()).isEqualTo(Status.UNKNOWN);assertThat(h.store.acks).isEmpty();
        assertThat(h.f.h.b.s).isEqualTo(((List<?>)before).getFirst());assertThat(h.f.h.b.stopped).isTrue();
        assertThat(h.f.trace()).containsSubsequence("recovery-confirm-identity","confirm-lock");
    }

    @Test void defaultStoreMethodsDenyWithoutAnyImplementorOptIn(){
        var h=new Harness();var claim=h.claim();
        var defaults=(OwnedRecoveryConfirmStore)Proxy.newProxyInstance(OwnedRecoveryConfirmStore.class.getClassLoader(),new Class<?>[]{OwnedRecoveryConfirmStore.class},(proxy,m,args)->{
            assertThat(m.isDefault()).as(m.getName()).isTrue();return InvocationHandler.invokeDefault(proxy,m,args);
        });
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls=List.of(
            ()->defaults.lockInTransaction(h.expected),()->defaults.insertAndClaimInTransaction(h.expected,"first",h.f.now(),h.budget),
            ()->defaults.requireClaimInTransaction(h.expected,claim,h.budget,h.f.now()),
            ()->defaults.acknowledgeInTransaction(h.expected,claim,h.reply(),h.f.now(),h.budget),()->defaults.unknownInTransaction(h.expected,claim,h.budget));
        calls.forEach(call->assertThatThrownBy(call).isInstanceOf(RuntimeException.class));
    }

    @Test void budgetAndReplyAreBoundedValidatedAndRedacted(){
        var h=new Harness();assertThat(h.budget.deadlineAt()).isEqualTo(h.budget.enteredAt().plusSeconds(30));
        assertThat(h.newBudget().invocation()).isNotEqualTo(h.budget.invocation());var claim=h.claim();
        assertThat(h.budget.remainingNanos(claim)).isBetween(1L,30_000_000_000L);
        assertThat(h.reply().toString()).isEqualTo("ConfirmReply[redacted]");
        var ack=h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget);
        assertThat(ack.toString()).isEqualTo("ConfirmationAck[redacted]");
        assertThat(Modifier.isFinal(InvocationBudget.class.getModifiers())).isTrue();
    }

    @Test void interruptedCallerCannotClaimInspectOrAcknowledgeOrCreateAnyEffects(){
        for(var phase:List.of("claim","inspect","ack")){
            var h=new Harness();var claim=phase.equals("claim")?null:h.claim();var before=h.f.state();var row=h.store.row;h.store.calls.clear();
            boolean previous=Thread.interrupted();
            try{
                Thread.currentThread().interrupt();
                assertThatThrownBy(()->{switch(phase){case "claim"->h.claim();case "inspect"->h.service.inspectClaim(h.expected,claim,h.f.command(),h.budget);default->h.service.acknowledge(h.expected,claim,h.reply(),h.f.command(),h.budget);}}).isInstanceOf(RuntimeException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(h.store.calls).isEmpty();
                assertThat(h.store.row).isEqualTo(row);assertThat(h.store.acks).isEmpty();assertThat(h.f.state()).isEqualTo(before);
            }finally{Thread.interrupted();if(previous)Thread.currentThread().interrupt();}
        }
    }

    private static final class Harness extends OwnedRecoveryConfirmInertFixture {}
}
