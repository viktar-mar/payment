package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.*;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedRecoveryConfirmInertFixture;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedRecoveryPreparationMappingTest.*;
import static org.assertj.core.api.Assertions.*;

/** Actual original authentication and actual one-use service; only the official SDK transport is inert. */
class StripeBillingOwnedFailedRenewalRecoveryConfirmAdapterTest {
    @Test void originalSeventeenGetsPrecedeOneExactConfirmAndAckHasNoFinancialAuthority(){
        var f=new Fixture();f.queue();var before=f.g.local.state();var ack=f.confirm();
        assertThat(ack.operation()).isEqualTo(f.confirmation.row());assertThat(ack.operation().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(ack.operation().version()).isEqualTo(2);assertThat(ack.operation().providerReference()).isEqualTo("pi_renewal");
        assertThat(ack.reply()).isEqualTo(new ConfirmReply("pi_renewal","cus_owned","pm_owned","EUR",1000,1000,0,"ch_recovered",f.g.local.now()));
        assertThat(f.confirmation.acknowledgments()).containsExactly(ack);assertThat(f.g.local.state()).isEqualTo(before);
        var paths=new ArrayList<String>();paths.add("/v1/test_helpers/test_clocks/clock_owned");paths.addAll(GRAPH_PATHS);paths.add("/v1/test_helpers/test_clocks/clock_owned");paths.add("/v1/payment_intents/pi_renewal/confirm");
        assertThat(f.g.sdk.http.calls.stream().map(r->r.url().getPath()).toList()).isEqualTo(paths);
        assertThat(f.gets()).hasSize(17).allSatisfy(r->{assertThat(r.options().getIdempotencyKey()).isNull();f.options(r);});
        var post=f.posts().getFirst();assertThat(post.url().toString()).isEqualTo("https://api.stripe.com/v1/payment_intents/pi_renewal/confirm");
        assertThat(post.params()).containsExactlyInAnyOrderEntriesOf(Map.of("payment_method","pm_owned","off_session",true,"error_on_requires_action",true));
        assertThat(post.options().getIdempotencyKey()).isEqualTo(f.g.original.recoveryOperation().providerOperationKey()+":CONFIRM");f.options(post);
        assertThat(f.confirmation.budgetUses()).hasSizeGreaterThanOrEqualTo(3).allSatisfy(b->assertThat(b).isSameAs(f.confirmation.budgetUses().getFirst()));
        var budget=f.confirmation.budgetUses().getFirst();assertThat(budget.enteredAt()).isEqualTo(f.entry);assertThat(budget.deadlineAt()).isEqualTo(f.entry.plusSeconds(30));
        assertThat(f.g.sdk.http.replies).isEmpty();assertThat(f.g.local.trace()).doesNotContain("stop","facts","publication","binding-write");
        assertThat(ack.toString()).isEqualTo("ConfirmationAck[redacted]");assertThat(ack.reply().toString()).isEqualTo("ConfirmReply[redacted]");
        assertThat(f.adapter().toString()).doesNotContain("sk_test", "secret", "pi_renewal", "pm_owned", f.g.local.reservation().id().toString());
        var receipt=f.confirmation.receiptState();f.g.queueClocks();refused(f::confirm);assertThat(f.posts()).hasSize(1);assertThat(f.confirmation.receiptState()).isEqualTo(receipt);assertThat(f.g.local.state()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"completed-card","customer","original-amount","allocation","original-intent"})
    void changedPreflightDebtOrCompletedCardCannotClaimOrPost(String drift){
        var f=new Fixture();f.queue();int index=switch(drift){case "completed-card"->15;case "customer"->1;case "original-amount","original-intent"->11;default->10;};
        f.g.change(index,j->{var x=StripeBillingFailedRenewalMappingTest.first(j);switch(drift){case "completed-card"->x.addProperty("customer","cus_other");case "customer"->x.addProperty("id","cus_other");case "original-amount"->x.addProperty("amount",1001);case "original-intent"->x.addProperty("id","pi_other");case "allocation"->x.addProperty("id","inpay_other");default->throw new AssertionError(drift);}});
        var before=f.g.local.state();refused(f::confirm);assertThat(f.confirmation.row()).isNull();assertThat(f.posts()).isEmpty();assertThat(f.g.local.state()).isEqualTo(before);assertThat(f.g.local.trace()).doesNotContain("stop");
    }
    @Test void forgedOriginalCannotChooseCredentialClientOrReceiptLocators(){
        var f=new Fixture();var r=f.g.local.reservation();var forged=new Reservation(r.id(),r.admission(),r.failure(),new UUID(0,999),r.requestFingerprint(),r.initialOperationVersion(),r.binding(),r.billingSample(),r.reservedAt());
        var before=f.g.local.state();refused(()->f.adapter().confirm(forged,f.g.local.completion(),f.g.local.recovery(),f.g.local.command()));
        assertThat(f.clientCreations).hasValue(0);assertThat(f.g.sdk.http.calls).isEmpty();assertThat(f.confirmation.row()).isNull();assertThat(f.g.local.state()).isEqualTo(before);assertThat(f.g.local.trace()).doesNotContain("stop");
    }
    @ParameterizedTest @ValueSource(strings={"id","customer","method","currency","amount","received","capturable","capture","live","types","last-error","next-action","charge","charge-too-long","object","processing","requires_action","requires_payment_method","requires_confirmation","canceled"})
    void everyUnsupportedOrMismatchedReplyConsumesOnlyItsOwnClaimAndNeverRetries(String bad){
        var f=new Fixture();var body=f.body();switch(bad){
            case "id"->body.addProperty("id","pi_other");case "customer"->body.addProperty("customer","cus_other");case "method"->body.addProperty("payment_method","pm_other");
            case "currency"->body.addProperty("currency","usd");case "amount"->body.addProperty("amount",1001);case "received"->body.addProperty("amount_received",999);case "capturable"->body.addProperty("amount_capturable",1);
            case "capture"->body.addProperty("capture_method","manual");case "live"->body.addProperty("livemode",true);case "types"->body.add("payment_method_types",JsonParser.parseString("[\"card\",\"link\"]"));
            case "last-error"->body.add("last_payment_error",JsonParser.parseString("{\"message\":\"PRIVATE_PROVIDER_DETAIL\"}"));case "next-action"->body.add("next_action",JsonParser.parseString("{\"type\":\"use_stripe_sdk\"}"));
            case "charge"->body.addProperty("latest_charge","ch_bad/slash");case "charge-too-long"->body.addProperty("latest_charge","ch_"+"x".repeat(253));case "object"->body.addProperty("object","setup_intent");default->body.addProperty("status",bad);
        }
        f.g.queueClocks();f.g.sdk.http.reply(body.toString());refused(f::confirm);f.onlyOwnUnknown();
        var receipt=f.confirmation.receiptState();f.g.queueClocks();refused(f::confirm);assertThat(f.posts()).hasSize(1);assertThat(f.confirmation.receiptState()).isEqualTo(receipt);assertThat(f.g.local.trace().stream().filter("stop"::equals)).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"id","customer","payment_method","currency","amount","amount_received","amount_capturable","latest_charge","livemode","capture_method","payment_method_types","status","object","null","invalid-json","402","500","disconnect"})
    void malformedMissingErrorAndUncertainRepliesNeverAckOrFallback(String bad){
        var f=new Fixture();f.g.queueClocks();if(bad.equals("402")||bad.equals("500"))f.g.sdk.http.error(Integer.parseInt(bad));
        else if(bad.equals("disconnect"))f.g.sdk.http.disconnect();else{var b=f.body();b.remove(bad);f.g.sdk.http.reply(bad.equals("null")?"null":bad.equals("invalid-json")?"{PRIVATE_PROVIDER_DETAIL":b.toString());}
        refused(f::confirm);f.onlyOwnUnknown();
    }
    @Test void providerClientSecretIsNeitherRequiredNorExposed(){
        for(String secret:Arrays.asList(null,"PRIVATE_PROVIDER_DETAIL_secret_do_not_return")){var f=new Fixture();var b=f.body();if(secret!=null)b.addProperty("client_secret",secret);f.g.queueClocks();f.g.sdk.http.reply(b.toString());var ack=f.confirm();assertThat(ack.toString()+ack.reply()).doesNotContain("PRIVATE_PROVIDER_DETAIL","do_not_return","4242");assertThat(f.posts()).hasSize(1);}
    }
    @ParameterizedTest @ValueSource(strings={"mid-read","pre-post","post-write"})
    void freshOriginalAuthorityBracketsReadClaimWriteAndAtomicAck(String phase){
        var f=new Fixture();f.queue();if(phase.equals("mid-read"))f.g.sdk.http.afterResponse=f.g.local::revoke;
        if(phase.equals("pre-post"))f.confirmation.afterCommit(()->{if(f.confirmation.row()!=null&&f.confirmation.row().status()==ProviderOperation.Status.DISPATCHING)f.g.local.revoke();});
        if(phase.equals("post-write"))f.afterPost=f.g.local::revoke;
        refused(f::confirm);if(phase.equals("mid-read")){assertThat(f.confirmation.row()).isNull();assertThat(f.g.sdk.http.calls).hasSize(1);assertThat(f.g.local.trace()).doesNotContain("stop");}
        else{f.onlyOwnUnknown();assertThat(f.posts()).hasSize(phase.equals("pre-post")?0:1);}
    }
    @Test void postWriteLocalDebtSequenceDriftCannotAckButCanConsumeTheExactOriginalClaim(){
        var f=new Fixture();f.queue();f.afterPost=f.g.local::driftSequence;refused(f::confirm);f.onlyOwnUnknown();assertThat(f.posts()).hasSize(1);
    }
    @Test void successfulWriteNeverRereadsTheFailedRemoteGraphBeforeAck(){
        var f=new Fixture();f.queue();f.afterPost=()->{assertThat(f.g.sdk.http.replies).isEmpty();f.g.local.proof=null;};
        var ack=f.confirm();assertThat(ack.operation().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(f.gets()).hasSize(17);assertThat(f.posts()).hasSize(1);
    }
    @Test void oneImmutableBudgetStartsBeforeAuthenticationAndIsPassedIntoEveryReaderGet(){
        var f=new Fixture();var once=new AtomicBoolean();f.g.local.afterAuthentication(()->{if(once.compareAndSet(false,true))f.nanos.set(TimeUnit.SECONDS.toNanos(30));});
        refused(f::confirm);assertThat(f.clientCreations).hasValue(0);assertThat(f.g.sdk.http.calls).isEmpty();assertThat(f.confirmation.row()).isNull();assertThat(f.g.local.trace()).doesNotContain("stop");
        var second=new Fixture();second.queue();second.afterGet=()->second.nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));second.beforeConfirmClient=()->second.nanos.addAndGet(TimeUnit.SECONDS.toNanos(13));
        refused(second::confirm);assertThat(second.gets()).hasSize(17);assertThat(second.posts()).isEmpty();second.noAckOrFinancialChange();
    }
    @Test void readerConsumesTheExactSuppliedInvocationRatherThanOpeningAFreshThirtySeconds(){
        var f=new Fixture();f.g.queueClocks();var budget=new InvocationBudget(f.g.local.clock(),f.nanos::get);f.nanos.set(TimeUnit.SECONDS.toNanos(29));
        f.afterGet=()->f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertThatThrownBy(()->f.reader().inspect(f.g.local.reservation(),f.g.local.completion(),f.g.local.recovery(),f.g.local.command(),budget)).isInstanceOf(PaymentApplicationException.class);
        assertThat(f.gets()).hasSize(1);assertThat(f.posts()).isEmpty();assertThat(f.confirmation.row()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"utc-deadline","utc-rollback","injected-deadline","injected-rollback"})
    void immutableThirtySecondBudgetRejectsDeadlineAndRollbackAfterClosingPreflight(String clock){
        var f=new Fixture();f.queue();f.nanos.set(10);f.beforeConfirmClient=()->{switch(clock){case "utc-deadline"->f.g.local.at(f.entry.plusSeconds(30));case "utc-rollback"->f.g.local.at(f.entry.minusNanos(1));case "injected-deadline"->f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(30));case "injected-rollback"->f.nanos.decrementAndGet();default->throw new AssertionError(clock);}};
        refused(f::confirm);assertThat(f.gets()).hasSize(17);assertThat(f.posts()).isEmpty();f.noAckOrFinancialChange();
    }
    @Test void actualThirtySecondsIncludesBlockingOriginalAuthenticationWithStillUtcAndInjectedClocks(){
        var f=new Fixture();var once=new AtomicBoolean();f.g.local.afterAuthentication(()->{if(once.compareAndSet(false,true))pauseThirtySeconds();});
        long started=System.nanoTime();refused(f::confirm);assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(30),Duration.ofSeconds(40));
        assertThat(f.g.local.now()).isEqualTo(f.entry);assertThat(f.nanos).hasValue(0);assertThat(f.g.sdk.http.calls).isEmpty();assertThat(f.confirmation.row()).isNull();assertThat(f.clientCreations).hasValue(0);
    }
    @Test void actualThirtySecondBlockedAckRollsBackWhileOriginalSessionAndLeaseRemainLive(){
        var f=new Fixture();f.queue();var entered=new AtomicBoolean();var tentative=new AtomicReference<Object>();
        f.confirmation.afterEffect(()->{if(f.confirmation.row().status()==ProviderOperation.Status.ACKNOWLEDGED){entered.set(true);tentative.set(f.confirmation.receiptState());
            assertThat(f.g.local.now()).isBefore(f.g.original.enrollment().sessionExpiresAt()).isBefore(f.confirmation.row().firstDispatchedAt().plusSeconds(60));pauseThirtySeconds();
            assertThat(f.g.local.now()).isEqualTo(f.entry).isBefore(f.g.original.enrollment().sessionExpiresAt()).isBefore(f.confirmation.row().firstDispatchedAt().plusSeconds(60));}});
        long started=System.nanoTime();refused(f::confirm);assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(30),Duration.ofSeconds(40));
        assertThat(entered).isTrue();assertThat(tentative.get()).isNotNull();assertThat(f.nanos).hasValue(0);f.onlyOwnUnknown();assertThat(f.confirmation.receiptState()).isNotEqualTo(tentative.get());
    }
    @ParameterizedTest @ValueSource(strings={"injected","utc"})
    void ackMutationCrossingInvocationDeadlineRollsBackWithLiveSessionAndLease(String clock){
        var f=new Fixture();f.queue();f.confirmation.afterEffect(()->{if(f.confirmation.row().status()==ProviderOperation.Status.ACKNOWLEDGED){if(clock.equals("injected"))f.nanos.set(TimeUnit.SECONDS.toNanos(30));else f.g.local.at(f.entry.plusSeconds(30));assertThat(f.g.local.now()).isBefore(f.g.original.enrollment().sessionExpiresAt()).isBefore(f.confirmation.row().firstDispatchedAt().plusSeconds(60));}});
        refused(f::confirm);f.onlyOwnUnknown();assertThat(f.posts()).hasSize(1);
    }
    @Test void timelyCommittedAckSurvivesDelayedReturnBeyondInvocationSessionAndLease(){
        var f=new Fixture();f.queue();var committed=new AtomicReference<ConfirmationAck>();
        f.confirmation.afterCommit(()->{if(f.confirmation.row()!=null&&f.confirmation.row().status()==ProviderOperation.Status.ACKNOWLEDGED){committed.set(f.confirmation.acknowledgments().getFirst());f.nanos.set(TimeUnit.SECONDS.toNanos(31));f.g.local.at(f.g.original.enrollment().sessionExpiresAt().plusNanos(1));}});
        var ack=f.confirm();assertThat(ack).isSameAs(committed.get());assertThat(f.confirmation.row()).isEqualTo(ack.operation());assertThat(f.posts()).hasSize(1);assertThat(f.g.local.trace()).doesNotContain("stop");
    }
    @Test void rawOriginalSessionExpiryAtSdkWorkerEntryCannotBeRoundedOrReplaced(){
        var f=new Fixture();f.g.local.at(f.g.original.enrollment().sessionExpiresAt().minusNanos(1));f.entry=f.g.local.now();f.queue();
        var caller=Thread.currentThread();f.invocationClock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Thread.currentThread()==caller?f.g.local.now():f.g.original.enrollment().sessionExpiresAt();}};
        refused(f::confirm);assertThat(f.g.sdk.http.calls).isEmpty();assertThat(f.confirmation.row()).isNull();assertThat(f.g.local.trace()).doesNotContain("stop");
    }
    @Test void rawOriginalSessionExpiryDuringFinalPrePostInspectionRefusesWithLiveInvocationAndLease(){
        var f=new Fixture();f.g.local.at(f.g.original.enrollment().sessionExpiresAt().minusSeconds(10));f.entry=f.g.local.now();f.queue();var lockCount=new AtomicInteger();
        f.confirmation.afterLock(()->{if(lockCount.incrementAndGet()==2){assertThat(f.confirmation.row().leaseExpiresAt()).isAfter(f.g.original.enrollment().sessionExpiresAt());f.g.local.at(f.g.original.enrollment().sessionExpiresAt());}});
        refused(f::confirm);assertThat(f.posts()).isEmpty();f.onlyOwnUnknown();assertThat(f.confirmation.budgetUses().getFirst().deadlineAt()).isAfter(f.g.local.now());
    }
    @Test void originalRawSessionBoundaryAtConfirmWorkerEntryRefusesWithLiveInvocationAndLease(){
        var f=new Fixture();f.g.local.at(f.g.original.enrollment().sessionExpiresAt().minusSeconds(1));f.entry=f.g.local.now();f.queue();var caller=Thread.currentThread();
        f.invocationClock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Thread.currentThread()!=caller&&f.confirmation.row()!=null?f.g.original.enrollment().sessionExpiresAt():f.g.local.now();}};
        refused(f::confirm);assertThat(f.gets()).hasSize(17);assertThat(f.posts()).isEmpty();f.onlyOwnUnknown();
        assertThat(f.confirmation.budgetUses().getFirst().deadlineAt()).isAfter(f.g.original.enrollment().sessionExpiresAt());
        assertThat(f.confirmation.row().firstDispatchedAt().plusSeconds(60)).isAfter(f.g.original.enrollment().sessionExpiresAt());
    }
    @Test void privateClientFailureBeforeSdkCannotEscapeOrProduceFinancialEvidence(){
        var f=new Fixture();f.queue();f.beforeConfirmClient=()->{throw new IllegalStateException("PRIVATE_PROVIDER_DETAIL sk_test_secret_INERT");};
        refused(f::confirm);assertThat(f.gets()).hasSize(17);assertThat(f.posts()).isEmpty();f.noAckOrFinancialChange();
    }
    @Test void blockedConfirmHasActualTwoSecondCompleteCallBoundAndCancelsWithoutRetry()throws Exception{
        var f=new Fixture();f.g.queueClocks();var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var entries=new AtomicInteger();
        f.postTransport=request->{entries.incrementAndGet();entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){cancelled.countDown();throw new IllegalStateException("PRIVATE_PROVIDER_DETAIL");}};
        long started=System.nanoTime();refused(f::confirm);assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(1),Duration.ofSeconds(4));
        assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(entries).hasValue(1);f.onlyOwnUnknown();
    }
    @ParameterizedTest @ValueSource(strings={"injected","utc"})
    void completedConfirmAtExclusiveTwoSecondCallDeadlineIsUncertain(String clock){
        var f=new Fixture();f.queue();f.afterPost=()->{if(clock.equals("injected"))f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));else f.g.local.at(f.entry.plusSeconds(2));};refused(f::confirm);f.onlyOwnUnknown();assertThat(f.posts()).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"injected","utc"})
    void confirmWorkerScheduledAtExclusiveCallDeadlineCannotEnterSdk(String clock){
        var f=new Fixture();f.queue();var caller=Thread.currentThread();
        if(clock.equals("injected"))f.invocationNanos=()->Thread.currentThread()!=caller&&f.confirmation.row()!=null?TimeUnit.SECONDS.toNanos(2):0;
        else f.invocationClock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Thread.currentThread()!=caller&&f.confirmation.row()!=null?f.entry.plusSeconds(2):f.g.local.now();}};
        refused(f::confirm);assertThat(f.gets()).hasSize(17);assertThat(f.posts()).isEmpty();f.onlyOwnUnknown();
    }
    @Test void callerInterruptionBeforeEntryPreservesFlagAndCannotClaim(){
        var f=new Fixture();Thread.currentThread().interrupt();try{refused(f::confirm);assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(f.confirmation.row()).isNull();assertThat(f.clientCreations).hasValue(0);assertThat(f.g.local.trace()).doesNotContain("stop");}finally{Thread.interrupted();}
    }
    @Test void callerInterruptionDuringPostCancelsWorkerAndConsumesOnlyItsOwnClaim()throws Exception{
        var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var preserved=new AtomicBoolean();var entries=new AtomicInteger();
        var task=new FutureTask<Void>(()->{var f=new Fixture();f.g.queueClocks();f.postTransport=request->{entries.incrementAndGet();entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){cancelled.countDown();throw new IllegalStateException("PRIVATE_PROVIDER_DETAIL");}};
            try{refused(f::confirm);preserved.set(Thread.currentThread().isInterrupted());f.onlyOwnUnknown();return null;}finally{Thread.interrupted();}});
        var caller=Thread.ofVirtual().start(task);try{assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();caller.interrupt();task.get(2,TimeUnit.SECONDS);assertThat(preserved).isTrue();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(entries).hasValue(1);}finally{caller.interrupt();task.cancel(true);}
    }
    @Test void existingCommittedClaimCannotBeAdoptedOrStoppedByAnotherAdapterInvocation(){
        var f=new Fixture();var budget=new InvocationBudget(f.g.local.clock(),f.nanos::get);var claim=f.confirmation.service().claim(f.g.original,f.g.local.command(),"other-invocation",budget);var before=f.confirmation.receiptState();f.queue();
        refused(f::confirm);assertThat(f.posts()).isEmpty();assertThat(f.confirmation.receiptState()).isEqualTo(before);assertThat(f.confirmation.row()).isEqualTo(claim);assertThat(f.g.local.trace()).doesNotContain("stop");
    }
    @Test void ackStorageFailureRollsBackAppendAndOnlyThenConsumesOwnClaim(){
        var f=new Fixture();f.queue();f.confirmation.failAck(true);refused(f::confirm);f.onlyOwnUnknown();assertThat(f.g.local.trace()).containsSubsequence("confirm-ack","rollback","confirm-lock","stop","commit");
    }

    static void refused(Runnable call){assertThatThrownBy(call::run).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned recovery confirmation unavailable").hasNoCause().satisfies(e->{var p=(PaymentApplicationException)e;assertThat(p.code()).isEqualTo("OWNED_RECOVERY_CONFIRM_UNAVAILABLE");assertThat(p.status()).isEqualTo(409);assertThat(e.getSuppressed()).isEmpty();assertThat(e.toString()).doesNotContain("PRIVATE_PROVIDER_DETAIL","sk_test","secret_INERT","pm_","pi_");});}
    static void pauseThirtySeconds(){try{Thread.sleep(Duration.ofMillis(30100));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("inert checkpoint interrupted");}}
    interface MemoryPost {StripeResponse request(StripeRequest request)throws StripeException;}
    static final class Fixture {
        final Graph g=new Graph();final OwnedRecoveryConfirmInertFixture confirmation=new OwnedRecoveryConfirmInertFixture(g.local);
        final AtomicLong nanos=new AtomicLong();final AtomicInteger clientCreations=new AtomicInteger();final Thread caller=Thread.currentThread();
        final Object financialBefore=financial();Instant entry=g.local.now();Clock invocationClock=g.local.clock();LongSupplier invocationNanos=nanos::get;
        Runnable afterGet=()->{},afterPost=()->{},beforeConfirmClient=()->{};MemoryPost postTransport=g.sdk.http::request;
        final HttpClient memory=new HttpClient(){public StripeResponse request(StripeRequest request)throws StripeException{
            assertThat(Thread.currentThread()).isNotSameAs(caller);assertThat(g.local.transactions().active()).isFalse();
            if(request.method().name().equals("GET")){var result=g.sdk.http.request(request);afterGet.run();return result;}
            assertThat(request.method().name()).isEqualTo("POST");assertThat(confirmation.row()).isNotNull();assertThat(confirmation.row().status()).isEqualTo(ProviderOperation.Status.DISPATCHING);assertThat(confirmation.row().version()).isEqualTo(1);
            assertThat(g.local.now()).isBefore(confirmation.row().leaseExpiresAt()).isBefore(g.original.enrollment().sessionExpiresAt());
            var result=postTransport.request(request);afterPost.run();return result;
        }};
        final StripeClientFactory readClients=clientFactory(false),writeClients=clientFactory(true);
        StripeClientFactory clientFactory(boolean write){return new StripeClientFactory(){public StripeClient create(StripeCredentials credential){assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.local.transactions().active()).isFalse();clientCreations.incrementAndGet();if(write)beforeConfirmClient.run();return StripeClient.builder().setApiKey("sk_test_INERT").setMaxNetworkRetries(0).setHttpClient(memory).build();}};}
        StripeBillingOwnedFailedRenewalRecoveryReader reader(){return new StripeBillingOwnedFailedRenewalRecoveryReader(g.local.service(),g.local.proofs,g.local.providerSteps,g.sdk.credentials,readClients,g.sdk.digest,invocationClock,invocationNanos);}
        StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter adapter(){return new StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter(g.local.service(),reader(),confirmation.service(),g.sdk.credentials,writeClients,g.sdk.digest,invocationClock,invocationNanos);}
        ConfirmationAck confirm(){return adapter().confirm(g.local.reservation(),g.local.completion(),g.local.recovery(),g.local.command());}
        JsonObject body(){return JsonParser.parseString("{\"id\":\"pi_renewal\",\"object\":\"payment_intent\",\"livemode\":false,\"status\":\"succeeded\",\"customer\":\"cus_owned\",\"payment_method\":\"pm_owned\",\"currency\":\"eur\",\"amount\":1000,\"amount_received\":1000,\"amount_capturable\":0,\"capture_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"last_payment_error\":null,\"next_action\":null,\"latest_charge\":\"ch_recovered\"}").getAsJsonObject();}
        void queue(){g.queueClocks();g.sdk.http.reply(body().toString());}
        List<StripeRequest> posts(){return g.sdk.http.calls.stream().filter(r->r.method().name().equals("POST")).toList();}
        List<StripeRequest> gets(){return g.sdk.http.calls.stream().filter(r->r.method().name().equals("GET")).toList();}
        void options(StripeRequest r){assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.options().getConnectTimeout()).isBetween(1,2000);assertThat(r.options().getReadTimeout()).isBetween(1,2000);assertThat(r.options().getStripeAccount()).isNull();assertThat(r.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");}
        Object financial(){var state=(List<?>)g.local.state();return List.of(state.get(0),state.get(1),state.get(2),state.get(3),state.get(4),state.get(5),state.get(7));}
        void noAckOrFinancialChange(){assertThat(confirmation.acknowledgments()).isEmpty();assertThat(financial()).isEqualTo(financialBefore);}
        void onlyOwnUnknown(){noAckOrFinancialChange();assertThat(confirmation.row()).isNotNull();assertThat(confirmation.row().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(confirmation.row().version()).isEqualTo(2);assertThat(confirmation.row().resourceId()).isEqualTo(g.original.recoveryOperation().id());assertThat(confirmation.row().operationKey()).isEqualTo(g.original.recoveryOperation().providerOperationKey()+":CONFIRM");assertThat(g.local.trace().stream().filter("stop"::equals)).hasSize(1);}
    }
}
