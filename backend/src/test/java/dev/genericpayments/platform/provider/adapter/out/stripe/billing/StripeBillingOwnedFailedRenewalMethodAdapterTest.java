package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.*;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual service authority and independent earlier evidence; official SDK HTTP exists only in memory. */
class StripeBillingOwnedFailedRenewalMethodAdapterTest {
    @Test void originalBoundMethodMakesOneExactPostAndReturnsOnlyItsGenuineAck() {
        var f=new Fixture();f.reply();var ack=f.apply();
        assertThat(ack).isSameAs(f.step);assertThat(ack.kind()).isEqualTo(ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        assertThat(ack.accountId()).isEqualTo(f.g.account().id());assertThat(ack.resourceId()).isEqualTo(f.g.operation().id());
        assertThat(ack.operationKey()).isEqualTo(f.key());assertThat(ack.fingerprint()).isEqualTo(f.fingerprint());
        assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(ack.version()).isEqualTo(2);
        assertThat(ack.providerReference()).isEqualTo("sub_owned");assertThat(ack.retryBefore()).isEqualTo(ack.firstDispatchedAt().plusSeconds(23*3600));
        assertThat(ack.leaseOwner()).isNull();assertThat(ack.leaseExpiresAt()).isNull();
        assertThat(f.sdk.http.calls).hasSize(1);var request=f.sdk.http.calls.getFirst();
        assertThat(request.method().name()).isEqualTo("POST");assertThat(request.url().toString()).isEqualTo("https://api.stripe.com/v1/subscriptions/sub_owned");
        assertThat(request.params()).containsOnlyKeys("default_payment_method").containsEntry("default_payment_method","pm_original");
        assertThat(request.options().getIdempotencyKey()).isEqualTo(f.g.operation().providerOperationKey()+":METHOD");
        assertThat(request.options().getMaxNetworkRetries()).isZero();assertThat(request.options().getConnectTimeout()).isBetween(1,2000);assertThat(request.options().getReadTimeout()).isBetween(1,2000);
        assertThat(request.options().getStripeAccount()).isNull();assertThat(request.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");
        assertThat(f.events).containsSubsequence("reserve","claim","dispatch","sdk","ack");assertThat(f.sdk.verifications).isZero();assertThat(f.sdk.binds).isZero();
        assertThat(f.g.stops()).isZero();f.unchanged();
        // The adapter returns evidence; only this separate actual service transaction changes lifecycle.
        var processing=f.g.methods.recordMethodUpdateReply(f.g.reservation,f.g.context);
        assertThat(processing.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(processing.version()).isEqualTo(1);
    }
    @Test void committedBrowserConsumptionAndBoundOriginalEvidenceAreRequiredBeforeAnyMethodStep() {
        for(var bad:List.of("consumption","proof","unbound","setup-ack","setup-fingerprint")){
            var f=new Fixture();
            switch(bad){
                case "consumption"->f.g.browserConsumed(false);
                case "proof"->f.g.setupProof(null);
                case "unbound"->{var p=f.g.setupProof();f.g.setupProof(new Setup(p.operation(),p.subscription(),p.account(),p.step(),p.reference(),null));}
                case "setup-ack"->f.g.setupStep(copy(f.g.setupStep(),ProviderOperation.Status.UNKNOWN,null,f.g.now(),f.g.now().plusSeconds(23*3600),null,null,2));
                case "setup-fingerprint"->f.g.setupStep(identity(f.g.setupStep(),"fingerprint"));
            }
            refused(f::apply);assertThat(f.step).isNull();assertThat(f.events).doesNotContain("reserve","claim","sdk");assertThat(f.g.stops()).isZero();
        }
    }
    @Test void everyExistingMethodStateIsRefusedWithoutAdoptionOrStoppingItsHolder() {
        for(var status:ProviderOperation.Status.values()){
            var f=new Fixture();f.setStep(copy(f.fresh(),status,status==ProviderOperation.Status.ACKNOWLEDGED?"sub_owned":null,
                status==ProviderOperation.Status.RESERVED?null:f.g.now(),status==ProviderOperation.Status.RESERVED?null:f.g.now().plusSeconds(23*3600),
                status==ProviderOperation.Status.DISPATCHING?"other":null,status==ProviderOperation.Status.DISPATCHING?f.g.now().plusSeconds(60):null,status==ProviderOperation.Status.RESERVED?0:2));
            refused(f::apply);assertThat(f.events).doesNotContain("reserve","claim","sdk","ack","unknown");assertThat(f.g.stops()).isZero();
        }
    }
    @Test void successfulInvocationCannotRepeatOrAdoptItsOriginalAck() {
        var f=new Fixture();f.reply();var ack=f.apply();refused(f::apply);
        assertThat(f.step).isSameAs(ack);assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.g.stops()).isZero();f.unchanged();
    }
    @Test void writerBehindAuthenticatedSetupDispatchCannotReserveOrPost() {
        var f=new Fixture();var floor=f.originalSetup.firstDispatchedAt();
        f.invocationClock=Clock.fixed(floor.minusSeconds(1),ZoneOffset.UTC);f.reply();
        refused(f::apply);assertThat(f.step).isNull();
        assertThat(f.events).doesNotContain("reserve","claim-attempt","dispatch","sdk","unknown","ack");
        assertThat(f.sdk.http.calls).isEmpty();assertThat(f.g.stops()).isZero();f.unchanged();
    }
    @Test void writerMayClaimAtTheExactOriginalSetupDispatch() {
        var f=new Fixture();var floor=f.originalSetup.firstDispatchedAt();
        f.invocationClock=Clock.fixed(floor,ZoneOffset.UTC);f.reply();var ack=f.apply();
        assertThat(ack.firstDispatchedAt()).isEqualTo(floor);assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.g.stops()).isZero();f.unchanged();
    }
    @Test void actualClaimTimeBelowSetupFloorAfterReservationCannotConsumeFirstDispatch() {
        var f=new Fixture();var floor=f.originalSetup.firstDispatchedAt();
        // Both later samples stay inside the invocation budget, including its lower bound.
        var now=new AtomicReference<>(floor.minusSeconds(1));
        f.invocationClock=new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        f.on("find",()->now.set(floor.plusNanos(999)));
        f.on("reserve",()->now.set(floor.minusNanos(1)));
        f.reply();refused(f::apply);
        assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.RESERVED);assertThat(f.step.version()).isZero();
        assertThat(f.events).doesNotContain("claim-attempt","dispatch","sdk","unknown","ack");
        assertThat(f.sdk.http.calls).isEmpty();assertThat(f.g.stops()).isZero();f.unchanged();
    }
    @Test void foreignOriginalKeyAndClientAuthorityRefuseBeforeProviderEffects() {
        var f=new Fixture();var c=f.g.context;
        var changed=new SubscriptionCommandContext(c.actor(),c.siteId(),c.customerId(),c.origin(),"browser-confirm-key",c.requestId());
        refused(()->f.adapter().apply(f.g.reservation,changed));assertThat(f.step).isNull();assertThat(f.g.stops()).isZero();
        for(var bad:List.of("session","authority")){var g=new Fixture();g.g.drift(bad);refused(g::apply);assertThat(g.step).isNull();assertThat(g.g.stops()).isZero();}
    }
    @Test void claimLoserDoesNotStopTheActualFirstHolder() {
        var f=new Fixture();f.on("claim-attempt",()->f.setStep(f.claimed("competing-holder")));refused(f::apply);
        assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);assertThat(f.step.leaseOwner()).isEqualTo("competing-holder");
        assertThat(f.events).doesNotContain("sdk","ack","unknown");assertThat(f.g.stops()).isZero();
    }
    @Test void callerThatLostTheInsertMayWinTheFirstOriginalClaim() {
        var f=new Fixture();var inserted=f.fresh();f.setStep(inserted);f.hideFinds=1;f.reply();
        assertThat(f.apply().id()).isEqualTo(inserted.id());assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.g.stops()).isZero();f.unchanged();
    }
    @Test void malformedReservedIdentitiesAndNoninitialShapesNeverOwnCleanup() {
        for(var bad:List.of("id","kind","account","external","credentials","resource","key","fingerprint","status","version","reference","first","window","lease-owner","lease-expiry")){
            var f=new Fixture();f.reserveMutation=s->malformed(s,bad);refused(f::apply);
            assertThat(f.events).doesNotContain("claim","sdk","unknown");assertThat(f.g.stops()).isZero();
        }
    }
    @Test void malformedOrSecondClaimNeverStopsAnUnownedClaimant() {
        for(var bad:List.of("id","kind","account","external","credentials","resource","key","fingerprint","status","version","reference","first","window","lease-owner","lease-expiry")){
            var f=new Fixture();f.claimMutation=s->malformed(s,bad);refused(f::apply);
            assertThat(f.events).doesNotContain("sdk","unknown");assertThat(f.g.stops()).isZero();
        }
    }
    @Test void driftAfterReserveRefusesBeforeCleanupOwnership() {
        var f=new Fixture();f.on("reserve",()->f.g.drift("sequence"));refused(f::apply);
        assertThat(f.events).doesNotContain("claim","sdk","unknown");assertThat(f.g.stops()).isZero();
    }
    @Test void originalExpiryDuringInitialStepLookupRefusesBeforeAnyMethodReservation() {
        var f=new Fixture();f.g.at(f.expiry().minusMillis(500));var entry=f.g.now();
        f.on("find",()->f.g.at(f.expiry()));refused(f::apply);
        // The 500ms wait remains inside the immutable 30s invocation; session authentication
        // must refuse before reserve rather than relying on the unrelated invocation timeout.
        assertThat(Duration.between(entry,f.g.now())).isEqualTo(Duration.ofMillis(500));
        assertThat(f.step).isNull();assertThat(f.events).doesNotContain("reserve","claim","sdk","unknown");
        assertThat(f.sdk.http.calls).isEmpty();assertThat(f.g.stops()).isZero();f.unchanged();
    }
    @Test void originalAuthorityOrFullContextDriftDuringInitialLookupRefusesBeforeAnyMethodReservation() {
        for(var bad:List.of("session","authority","proof","setup","consumption","sequence","sample","binding","operation")){
            var f=new Fixture();f.on("find",()->f.drift(bad));refused(f::apply);
            assertThat(f.step).as(bad).isNull();assertThat(f.events).doesNotContain("reserve","claim","sdk","unknown");
            assertThat(f.sdk.http.calls).isEmpty();assertThat(f.g.stops()).isZero();
        }
    }
    @Test void fullFreshContextDetectsAuthorityAndIndependentProofDriftAfterClaim() {
        for(var boundary:List.of("claim","dispatch","client","response"))for(var bad:List.of("authority","session","sequence","sample","binding","operation","proof","setup","consumption")){
            var f=new Fixture();f.reply();f.on(boundary,()->f.drift(bad));refused(f::apply);
            assertThat(f.step.status()).as(boundary+"/"+bad).isEqualTo(ProviderOperation.Status.UNKNOWN);
            assertThat(f.events).doesNotContain("ack");assertThat(f.g.stops()).isEqualTo(1);
            assertThat(f.sdk.http.calls).hasSize(boundary.equals("response")?1:0);
        }
    }
    @Test void typedDispatchMustReturnTheExactOriginalClaim() {
        var f=new Fixture();f.dispatchMutation=s->identity(s,"fingerprint");refused(f::apply);
        assertThat(f.sdk.http.calls).isEmpty();assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void malformedOrForeignSubscriptionEvidenceNeverAcknowledges() {
        for(var bad:List.of("id","object","livemode","customer","default_payment_method"))for(var shape:List.of("missing","null","foreign")){
            var f=new Fixture();var body=f.body();
            if(shape.equals("missing"))body.remove(bad);else if(shape.equals("null"))body.add(bad,JsonNull.INSTANCE);else if(bad.equals("livemode"))body.addProperty(bad,true);else body.addProperty(bad,"foreign");
            f.sdk.http.reply(body.toString());refused(f::apply);assertThat(f.events).doesNotContain("ack");assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(f.g.stops()).isEqualTo(1);
        }
        for(var body:List.of("null","{}")){var f=new Fixture();f.sdk.http.reply(body);refused(f::apply);assertThat(f.events).doesNotContain("ack");assertThat(f.g.stops()).isEqualTo(1);}
    }
    @Test void transportUncertaintyStopsOnceAndNeverRepeatsThePost() {
        for(var disconnect:List.of(false,true)){
            var f=new Fixture();if(disconnect)f.sdk.http.disconnect();else f.sdk.http.error(500);refused(f::apply);
            assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(f.g.stops()).isEqualTo(1);assertThat(f.sdk.http.calls).hasSize(1);
            refused(f::apply);assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.g.stops()).isEqualTo(1);f.unchanged();
        }
    }
    @Test void precommitAckFailureUnknownsOnlyItsStillMatchingClaim() {
        var f=new Fixture();f.reply();f.on("before-ack",()->{throw new IllegalStateException("private ack failure");});refused(f::apply);
        assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(f.events.stream().filter("unknown"::equals).count()).isEqualTo(1);assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void changedDispatchReceiptIsNotUnknownedByFormerHolder() {
        var f=new Fixture();f.reply();f.on("before-ack",()->{f.setStep(copy(f.step,ProviderOperation.Status.DISPATCHING,null,f.step.firstDispatchedAt(),f.step.retryBefore(),"replacement",f.step.leaseExpiresAt(),2));throw new IllegalStateException("claim changed");});
        refused(f::apply);assertThat(f.events).doesNotContain("unknown");assertThat(f.step.leaseOwner()).isEqualTo("replacement");assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void committedAckSurvivesThrownFailureAndCannotAuthorizeAnotherPost() {
        var f=new Fixture();f.reply();f.on("ack",()->{throw new IllegalStateException("after actual ACK commit");});refused(f::apply);var ack=f.step;
        assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(ack.version()).isEqualTo(2);assertThat(f.events).doesNotContain("unknown");assertThat(f.g.stops()).isEqualTo(1);
        refused(f::apply);assertThat(f.step).isSameAs(ack);assertThat(f.sdk.http.calls).hasSize(1);f.unchanged();
    }
    @Test void malformedReturnedAckCannotBeAcceptedOrDowngradeARealCommittedAck() {
        for(var bad:List.of("id","kind","account","external","credentials","resource","key","fingerprint","status","version","reference","first","window","lease-owner","lease-expiry")){
            var f=new Fixture();f.reply();f.ackMutation=s->malformed(s,bad);refused(f::apply);
            assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(f.events).doesNotContain("unknown");assertThat(f.g.stops()).isEqualTo(1);
        }
    }
    @Test void failedUnknownFindOrStopDoesNotGiveReplacementAuthority() {
        for(var boundary:List.of("findById","unknown","stop")){
            var f=new Fixture();f.sdk.http.disconnect();if(boundary.equals("stop"))f.g.failStop();else f.on(boundary,()->{throw new IllegalStateException("private cleanup detail");});
            refused(f::apply);assertThat(f.g.stops()).isEqualTo(1);assertThat(f.step.status()).isEqualTo(boundary.equals("stop")?ProviderOperation.Status.UNKNOWN:ProviderOperation.Status.DISPATCHING);
            refused(f::apply);assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.g.stops()).isEqualTo(1);
        }
    }
    @Test void finalAckMayCommitAfterExpiryOrRevocationAndLaterActualLifecycleTransitionRefuses() {
        for(var mutation:List.of("expired","revoked")){
            var f=new Fixture();f.reply();f.on("ack",()->{if(mutation.equals("expired"))f.g.at(f.expiry());else f.g.revoke();});
            var ack=f.apply();assertThat(ack).isSameAs(f.step);assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(f.g.stops()).isZero();f.unchanged();
            assertThatThrownBy(()->f.g.methods.recordMethodUpdateReply(f.g.reservation,f.g.context))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage(mutation.equals("expired")?"authority expired":"authority revoked");
            assertThat(f.step).isSameAs(ack);assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);f.unchanged();
            refused(f::apply);assertThat(f.sdk.http.calls).hasSize(1);
        }
    }
    @Test void utcAndInjectedBudgetsRemainImmutableAcrossBlockingCallerWork() {
        for(var boundary:List.of("reserve","claim","dispatch","client","response"))for(var clock:List.of("utc","nanos")){
            var f=new Fixture();f.reply();var entry=f.g.now();f.on(boundary,()->{if(clock.equals("utc"))f.g.at(entry.plusSeconds(30));else f.nanos.set(TimeUnit.SECONDS.toNanos(30));});refused(f::apply);
            assertThat(f.events).doesNotContain("ack");assertThat(f.sdk.http.calls).hasSize(boundary.equals("response")?1:0);assertThat(f.g.stops()).isEqualTo(boundary.equals("reserve")?0:1);
            if(clock.equals("nanos"))assertThat(f.g.now()).isEqualTo(entry);
        }
    }
    @Test void originalSessionExpiryDuringAccountLockCannotSaveProcessingAfterActualAck() {
        var f=new Fixture();f.reply();var ack=f.apply();
        f.g.afterAccountLock(()->f.g.at(f.expiry()));
        assertThatThrownBy(()->f.g.methods.recordMethodUpdateReply(f.g.reservation,f.g.context))
            .isExactlyInstanceOf(IllegalStateException.class).hasMessage("browser unavailable");
        f.retainedAck(ack);
    }
    @Test void managementRevalidationAfterLastEnrollmentReadRefusesLostAuthorityOrExpiry() {
        for(var boundary:List.of("revoked","expired")){
            var f=new Fixture();f.reply();var ack=f.apply();
            f.g.afterReplyEnrollment(()->{if(boundary.equals("expired"))f.g.at(f.expiry());else f.g.drift("authority");});
            assertThatThrownBy(()->f.g.methods.recordMethodUpdateReply(f.g.reservation,f.g.context))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("authority "+boundary);
            f.retainedAck(ack);
        }
    }
    @Test void secondLiveCustomerSessionCannotSaveAnEnrolledReplyAfterOriginalRevocation() {
        var f=new Fixture();f.reply();var ack=f.apply();
        var second=f.g.secondSession();f.g.revoke();
        assertThatThrownBy(()->f.g.methods.recordMethodUpdateReply(f.g.reservation,second))
            .isExactlyInstanceOf(IllegalStateException.class).hasMessage("browser unavailable");
        f.retainedAck(ack);
    }
    @Test void backwardsUtcOrMonotonicAndOriginalLeaseExpiryRefuseBeforePost() {
        for(var mutation:List.of("backwards-utc","backwards-nanos","lease")){
            var f=new Fixture();f.on("dispatch",()->{switch(mutation){case "backwards-utc"->f.g.at(f.g.now().minusNanos(1));case "backwards-nanos"->f.nanos.set(-1);case "lease"->f.g.at(f.g.now().plusSeconds(60));}});
            refused(f::apply);assertThat(f.sdk.http.calls).isEmpty();assertThat(f.g.stops()).isEqualTo(1);
        }
    }
    @Test void completeCallUtcLimitCannotBeResetByFastResponse() {
        var f=new Fixture();f.reply();f.on("response",()->f.g.at(f.g.now().plusSeconds(2)));refused(f::apply);
        assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.events).doesNotContain("ack");assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void workerEntryCannotExtendImmutableCallInvocationOrLeaseDeadlines() {
        for(long late:new long[]{2,30,60}){
            var f=new Fixture();f.reply();var entry=f.g.now();f.invocationClock=f.workerClock(entry,entry.plusSeconds(late));refused(f::apply);
            assertThat(f.sdkEntries).hasValue(0);assertThat(f.sdk.http.calls).isEmpty();assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(f.g.stops()).isEqualTo(1);f.unchanged();
        }
    }
    @Test void workerEntryEnforcesInjectedMonotonicBudgetWithFixedUtc() {
        var f=new Fixture();f.reply();var entry=f.g.now();f.invocationNanos=()->Thread.currentThread()==f.caller?0:TimeUnit.SECONDS.toNanos(30);refused(f::apply);
        assertThat(f.g.now()).isEqualTo(entry);assertThat(f.sdkEntries).hasValue(0);assertThat(f.sdk.http.calls).isEmpty();assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void workerEntryAtExactOriginalSessionExpiryCannotBeginSdkWithinOtherBudgets() {
        var f=new Fixture();var entry=f.expiry().minusMillis(500);f.g.at(entry);f.reply();f.invocationClock=f.workerClock(entry,f.expiry());refused(f::apply);
        assertThat(f.sdkEntries).hasValue(0);assertThat(f.sdk.http.calls).isEmpty();assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(f.g.stops()).isEqualTo(1);f.unchanged();
    }
    @Test void alreadyInterruptedCallerPreservesFlagAndNeverReserves() {
        var f=new Fixture();Thread.currentThread().interrupt();try{refused(f::apply);assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(f.step).isNull();assertThat(f.sdk.http.calls).isEmpty();}finally{Thread.interrupted();}
    }
    @Test void realCompleteCallBoundCancelsBlockedMemoryTransport()throws Exception {
        var f=new Fixture();var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        f.transport=request->{entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){cancelled.countDown();throw new IllegalStateException("private transport detail");}};
        long started=System.nanoTime();refused(f::apply);assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(4));
        assertThat(entered.getCount()).isZero();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(f.sdkEntries).hasValue(1);assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void interruptionDuringSdkPreservesCallerFlagAndCancelsWorkerOnly()throws Exception {
        var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var preserved=new AtomicBoolean();var entries=new AtomicInteger();
        var task=new FutureTask<Void>(()->{var f=new Fixture();f.transport=request->{entries.incrementAndGet();entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){cancelled.countDown();throw new IllegalStateException("private transport detail");}};
            try{refused(f::apply);preserved.set(Thread.currentThread().isInterrupted());assertThat(f.g.stops()).isEqualTo(1);return null;}finally{Thread.interrupted();}});
        var caller=Thread.ofVirtual().start(task);try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();caller.interrupt();task.get(2,TimeUnit.SECONDS);assertThat(preserved).isTrue();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(entries).hasValue(1);}finally{caller.interrupt();task.cancel(true);}
    }

    static void refused(Runnable action){assertThatThrownBy(action::run).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned method application unavailable").hasNoCause().satisfies(failure->{var e=(PaymentApplicationException)failure;assertThat(e.code()).isEqualTo("OWNED_METHOD_APPLY_UNAVAILABLE");assertThat(e.status()).isEqualTo(409);assertThat(e.getSuppressed()).isEmpty();});}
    static ProviderOperation copy(ProviderOperation s,ProviderOperation.Status status,String reference,Instant first,Instant window,String owner,Instant lease,long version){return StripeBillingInertSdkFixture.copy(s,status,reference,first,window,owner,lease,version);}
    static ProviderOperation identity(ProviderOperation s,String bad){
        return new ProviderOperation(
            bad.equals("id")?null:s.id(),
            bad.equals("account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(UUID.randomUUID()):s.accountId(),
            bad.equals("external")?"acct_foreign":s.externalAccountId(),
            bad.equals("credentials")?"foreign":s.credentialsRef(),
            bad.equals("kind")?ProviderOperation.Kind.SETUP_INTENT_CREATE:s.kind(),
            bad.equals("resource")?UUID.randomUUID():s.resourceId(),
            bad.equals("key")?"foreign:METHOD":s.operationKey(),
            bad.equals("fingerprint")?"foreign":s.fingerprint(),
            s.status(),
            s.providerReference(),
            s.firstDispatchedAt(),
            s.retryBefore(),
            s.leaseOwner(),
            s.leaseExpiresAt(),
            s.version());
    }
    static ProviderOperation malformed(ProviderOperation s,String bad){
        if(List.of("id","kind","account","external","credentials","resource","key","fingerprint").contains(bad))return identity(s,bad);
        return copy(s,
            bad.equals("status")?ProviderOperation.Status.UNKNOWN:s.status(),
            bad.equals("reference")?"foreign":s.providerReference(),
            bad.equals("first")?(s.firstDispatchedAt()==null?Instant.EPOCH:s.firstDispatchedAt().plusNanos(1)):s.firstDispatchedAt(),
            bad.equals("window")?(s.retryBefore()==null?Instant.EPOCH:s.retryBefore().plusNanos(1)):s.retryBefore(),
            bad.equals("lease-owner")?"foreign":s.leaseOwner(),
            bad.equals("lease-expiry")?(s.leaseExpiresAt()==null?Instant.EPOCH:s.leaseExpiresAt().plusNanos(1)):s.leaseExpiresAt(),
            bad.equals("version")?s.version()+1:s.version());
    }
    @FunctionalInterface interface MemoryResponse {StripeResponse request(StripeRequest request)throws StripeException;}
    static final class Fixture {
        final OwnedMethodSetupInertFixture g=new OwnedMethodSetupInertFixture();final StripeBillingInertSdkFixture sdk=new StripeBillingInertSdkFixture();
        final Thread caller=Thread.currentThread();final List<String> events=new ArrayList<>();final Map<String,Runnable> hooks=new HashMap<>();
        final AtomicLong nanos=new AtomicLong();final AtomicInteger sdkEntries=new AtomicInteger();
        Clock invocationClock=g.clock;LongSupplier invocationNanos=nanos::get;ProviderOperation step;int hideFinds;
        UnaryOperator<ProviderOperation> reserveMutation=UnaryOperator.identity(),claimMutation=UnaryOperator.identity(),dispatchMutation=UnaryOperator.identity(),ackMutation=UnaryOperator.identity();
        MemoryResponse transport=sdk.http::request;final Setup originalProof;final ProviderOperation originalSetup;
        Fixture(){g.bindOriginalSetup(sdk.digest);originalProof=g.setupProof();originalSetup=g.setupStep();g.afterAccountLock(()->{assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.active()).isTrue();});}
        final ProviderOperationStore steps=StripeBillingInertSdkFixture.port(ProviderOperationStore.class,call->{
            caller();var a=call.args();return switch(call.name()){
                case "find"->{assertThat(a).containsExactly(g.account().id(),key());event("find");yield hideFinds-->0?Optional.empty():Optional.ofNullable(step);}
                case "findById"->{assertThat(a[0]).isEqualTo(step.id());event("findById");yield Optional.of(step);}
                case "reserveOwnedFailedRenewalMethodStep"->{assertThat(a).containsExactly(g.account(),g.reservation,ProviderOperation.Kind.SUBSCRIPTION_UPDATE);if(step==null)setStep(reserveMutation.apply(fresh()));var result=step;event("reserve");yield result;}
                case "claim"->{assertThat(a[0]).isEqualTo(step.id());assertThat(a[1]).isInstanceOf(String.class);assertThat((String)a[1]).isNotBlank();assertThat(a[3]).isEqualTo(Duration.ofSeconds(60));event("claim-attempt");if(step.status()!=ProviderOperation.Status.RESERVED)yield Optional.empty();var at=(Instant)a[2];setStep(claimMutation.apply(copy(step,ProviderOperation.Status.DISPATCHING,null,at,at.plusSeconds(23*3600),(String)a[1],at.plusSeconds(60),1)));var result=step;event("claim");yield Optional.of(result);}
                case "requireOwnedFailedRenewalMethodDispatch"->{assertThat(a[0]).isEqualTo(g.account());assertThat(a[1]).isEqualTo(g.reservation);assertThat(a[2]).isEqualTo(step);new ProviderOperationGuard().requireDispatch(step,g.account(),fingerprint(),(Instant)a[3]);var result=dispatchMutation.apply(step);event("dispatch");yield result;}
                case "acknowledge"->{assertClaim(a);assertThat(a[3]).isEqualTo("sub_owned");event("before-ack");setStep(copy(step,ProviderOperation.Status.ACKNOWLEDGED,(String)a[3],step.firstDispatchedAt(),step.retryBefore(),null,null,2));event("ack");yield ackMutation.apply(step);}
                case "unknown"->{assertClaim(a);event("unknown");setStep(copy(step,ProviderOperation.Status.UNKNOWN,null,step.firstDispatchedAt(),step.retryBefore(),null,null,2));yield step;}
                default->throw new AssertionError("Unplanned step capability "+call.name());
            };
        });
        final StripeClientFactory clients=new StripeClientFactory(){@Override public StripeClient create(StripeCredentials ignored){caller();event("client");return StripeClient.builder().setApiKey("sk_test_INERT").setHttpClient(new HttpClient(){@Override public StripeResponse request(StripeRequest request)throws StripeException{
            assertThat(Thread.currentThread()).isNotSameAs(caller);assertThat(g.active()).isFalse();sdkEntries.incrementAndGet();event("sdk");var response=transport.request(request);event("response");return response;
        }}).build();}};
        StripeBillingOwnedFailedRenewalMethodAdapter adapter(){return new StripeBillingOwnedFailedRenewalMethodAdapter(g.methods,steps,sdk.credentials,clients,sdk.digest,invocationClock,invocationNanos);}
        ProviderOperation apply(){return adapter().apply(g.reservation,g.context);}
        void caller(){assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.active()).isFalse();}
        void setStep(ProviderOperation value){step=value;g.providerStep(value);}
        void on(String boundary,Runnable action){hooks.put(boundary,action);}
        void event(String boundary){events.add(boundary);var action=hooks.remove(boundary);if(action!=null)action.run();}
        void drift(String bad){switch(bad){case "proof"->{var p=g.setupProof();g.setupProof(new Setup(p.operation(),p.subscription(),p.account(),p.step(),p.reference(),"pm_changed"));}case "setup"->g.setupStep(copy(g.setupStep(),ProviderOperation.Status.ACKNOWLEDGED,"seti_foreign",g.setupStep().firstDispatchedAt(),g.setupStep().retryBefore(),null,null,2));case "consumption"->g.browserConsumed(false);default->g.drift(bad);}}
        void unchanged(){g.noBusinessChanges();assertThat(g.setupProof()).isEqualTo(originalProof);assertThat(g.setupStep()).isEqualTo(originalSetup);}
        void retainedAck(ProviderOperation ack){
            assertThat(step).isSameAs(ack);assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
            assertThat(step.version()).isEqualTo(2);unchanged();
            refused(this::apply);assertThat(step).isSameAs(ack);assertThat(sdk.http.calls).hasSize(1);
        }
        String key(){return g.operation().providerOperationKey()+":METHOD";}
        String fingerprint(){var o=g.operation();return StripeRequestFingerprint.of(sdk.digest,g.account(),"SUBSCRIPTION_METHOD_V1",o.id().toString(),key(),o.fingerprint(),g.subscription().providerReference(),g.binding().providerReference(),g.setupProof().step().toString(),g.setupProof().reference(),g.setupProof().method(),"consent:true");}
        ProviderOperation fresh(){return new ProviderOperation(UUID.randomUUID(),g.account().id(),g.account().externalAccountId(),g.account().credentialsRef(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE,g.operation().id(),key(),fingerprint(),ProviderOperation.Status.RESERVED,null,null,null,null,null,0);}
        ProviderOperation claimed(String owner){return copy(step,ProviderOperation.Status.DISPATCHING,null,g.now(),g.now().plusSeconds(23*3600),owner,g.now().plusSeconds(60),1);}
        Instant expiry(){return ((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)g.context.actor()).session().expiresAt();}
        Clock workerClock(Instant entry,Instant worker){return new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Thread.currentThread()==caller?entry:worker;}};}
        JsonObject body(){var body=new JsonObject();body.addProperty("id","sub_owned");body.addProperty("object","subscription");body.addProperty("livemode",false);body.addProperty("customer","cus_owned");body.addProperty("default_payment_method","pm_original");return body;}
        void reply(){sdk.http.reply(body().toString());}
        void assertClaim(Object[] a){assertThat(step.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);assertThat(a[0]).isEqualTo(step.id());assertThat(a[1]).isEqualTo(step.leaseOwner());assertThat(a[2]).isEqualTo(step.version());}
    }
}
