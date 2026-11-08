package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.net.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
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

/** Actual authentication/service/fact lineage plus the official SDK over queued memory transport only. */
class StripeBillingOwnedFailedRenewalRecoveryReaderTest {
    static StripeBillingOwnedFailedRenewalRecoveryReader reader(Graph g){return reader(g,g.sdk.clients,g.local.clock(),System::nanoTime);}
    static StripeBillingOwnedFailedRenewalRecoveryReader reader(Graph g,StripeClientFactory clients,Clock clock,LongSupplier nanos){return new StripeBillingOwnedFailedRenewalRecoveryReader(g.local.service(),g.local.proofs,g.local.providerSteps,g.sdk.credentials,clients,g.sdk.digest,clock,nanos);}
    static StripeBillingOwnedFailedRenewalRecoveryReader.PreparedRecovery inspect(Graph g,StripeBillingOwnedFailedRenewalRecoveryReader reader){return reader.inspect(g.local.reservation(),g.local.completion(),g.local.recovery(),g.local.command());}
    static void refused(Runnable call){assertThatThrownBy(call::run).isExactlyInstanceOf(PaymentApplicationException.class).satisfies(e->{var x=(PaymentApplicationException)e;assertThat(x.code()).isEqualTo("OWNED_RECOVERY_PREFLIGHT_UNAVAILABLE");assertThat(x.status()).isEqualTo(409);}).hasMessage("Owned recovery preflight unavailable").hasNoCause().satisfies(e->assertThat(e.getSuppressed()).isEmpty());}
    static void noEffects(Graph g,Object before){assertThat(g.local.state()).isEqualTo(before);assertThat(g.local.trace()).doesNotContain("stop","prepared","claim","reservation","facts","completion","publication","binding-write");assertThat(g.sdk.rows).isEmpty();assertThat(g.sdk.claims).isZero();assertThat(g.sdk.binds).isZero();}
    @Test void authenticOriginalPreparationIsFreshlyReadAroundSeventeenPinnedGetsAndIsRepeatable(){
        var g=new Graph();g.queueClocks();var before=g.local.state();var result=inspect(g,reader(g));
        assertThat(result.original()).isEqualTo(g.original);assertThat(result.snapshot().status().name()).isEqualTo("PAST_DUE");assertThat(result.snapshot().paymentMethodReady()).isTrue();
        assertThat(result.snapshot().invoices().getFirst().cashObjects()).hasSize(1);assertThat(result.snapshot().invoices().getLast().cashObjects()).isEmpty();
        assertThat(result.snapshot().operationKey()).isNull();assertThat(result.snapshot().operationObservation()).isNull();assertThat(result.snapshot().recoveryObservations()).isEmpty();
        assertThat(result.toString()).isEqualTo("PreparedRecovery[redacted]");assertThat(result.toString()).doesNotContain("INERT","4242","do_not_return","seti_","pm_",g.local.reservation().id().toString());
        var expected=new ArrayList<String>();expected.add("/v1/test_helpers/test_clocks/clock_owned");expected.addAll(GRAPH_PATHS);expected.add("/v1/test_helpers/test_clocks/clock_owned");
        assertThat(g.sdk.http.calls.stream().map(r->r.url().getPath()).toList()).isEqualTo(expected);
        assertThat(g.sdk.http.calls).allSatisfy(r->{assertThat(r.method().name()).isEqualTo("GET");assertThat(r.options().getIdempotencyKey()).isNull();assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.options().getConnectTimeout()).isBetween(1,2000);assertThat(r.options().getReadTimeout()).isBetween(1,2000);assertThat(r.headers().map().get("Stripe-Version")).containsExactly(StripeBillingTestClockAdapter.API_VERSION);});
        assertThat(g.local.proofLocators).hasSizeGreaterThanOrEqualTo(35).allSatisfy(x->assertThat(x).isEqualTo(List.of(g.original.account().id(),g.original.completion().setupOperationId())));
        assertThat(g.local.stepLocators).containsOnly(g.original.completion().setupStepId(),g.original.completion().methodStepId());
        assertThat(g.local.trace().stream().filter("recovery-enrollment"::equals).count()).isGreaterThanOrEqualTo(35);noEffects(g,before);
        g.queueClocks();assertThat(inspect(g,reader(g)).original()).isEqualTo(g.original);assertThat(g.sdk.http.calls).hasSize(34);noEffects(g,before);
    }
    @Test void forgedReservationCannotChooseCredentialClientProofOrProviderLocators(){
        var g=new Graph();var before=g.local.state();var r=g.local.reservation();var forged=new Reservation(r.id(),r.admission(),r.failure(),new UUID(0,999),r.requestFingerprint(),r.initialOperationVersion(),r.binding(),r.billingSample(),r.reservedAt());
        var clients=new StripeClientFactory(){public StripeClient create(StripeCredentials ignored){throw new AssertionError("Unauthorized client creation");}};
        refused(()->reader(g,clients,g.local.clock(),System::nanoTime).inspect(forged,g.local.completion(),g.local.recovery(),g.local.command()));
        assertThat(g.local.proofLocators).isEmpty();assertThat(g.local.stepLocators).isEmpty();assertThat(g.sdk.http.calls).isEmpty();noEffects(g,before);
    }
    @Test void replacementSessionExpiryCannotChangeOriginalEnrollment(){
        var g=new Graph();var c=g.local.command();var s=((SubscriptionActor.Customer)c.actor()).session();
        var changed=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(s.sessionId(),s.siteId(),s.customerId(),s.origin(),s.scopes(),s.expiresAt().plusNanos(1));
        var forged=new SubscriptionCommandContext(new SubscriptionActor.Customer(changed),c.siteId(),c.customerId(),c.origin(),c.idempotencyKey(),c.requestId());
        var before=g.local.state();refused(()->reader(g).inspect(g.local.reservation(),g.local.completion(),g.local.recovery(),forged));assertThat(g.sdk.http.calls).isEmpty();assertThat(g.local.proofLocators).isEmpty();noEffects(g,before);
    }
    @ParameterizedTest @ValueSource(strings={"missing-proof","foreign-proof","missing-step","foreign-step"})
    void exactAuthenticatedOriginalProofAndBothProviderStepsAreRequiredBeforeSdk(String bad){
        var g=new Graph();var before=g.local.state();var p=g.local.proof;
        if(bad.equals("missing-proof"))g.local.proof=null;
        if(bad.equals("foreign-proof"))g.local.proof=new Setup(new UUID(0,999),p.subscription(),p.account(),p.step(),p.reference(),p.method());
        if(bad.equals("missing-step"))g.local.steps.remove(g.original.completion().methodStepId());
        if(bad.equals("foreign-step")){var s=g.local.steps.get(g.original.completion().setupStepId());g.local.steps.put(s.id(),new ProviderOperation(s.id(),s.accountId(),s.externalAccountId(),"foreign",s.kind(),s.resourceId(),s.operationKey(),s.fingerprint(),s.status(),s.providerReference(),s.firstDispatchedAt(),s.retryBefore(),null,null,s.version()));}
        refused(()->inspect(g,reader(g)));assertThat(g.sdk.http.calls).isEmpty();noEffects(g,before);
    }
    @ParameterizedTest @ValueSource(strings={"authority","proof","step","sequence"})
    void eachCompletedGetMustRecheckOriginalAuthorityAndImmutableEvidence(String drift){
        var g=new Graph();g.queueClocks();var before=g.local.state();g.sdk.http.afterResponse=()->{switch(drift){case "authority"->g.local.revoke();case "proof"->{var p=g.local.proof;g.local.proof=new Setup(p.operation(),p.subscription(),p.account(),p.step(),p.reference(),"pm_other");}case "step"->g.local.steps.remove(g.original.completion().methodStepId());case "sequence"->g.local.driftSequence();default->throw new AssertionError(drift);}};
        refused(()->inspect(g,reader(g)));assertThat(g.sdk.http.calls).hasSize(1);if(!drift.equals("sequence"))noEffects(g,before);else assertThat(g.local.trace()).doesNotContain("stop","claim","publication");
    }
    @Test void originalSessionExclusiveNanosecondExpiryIsCheckedAfterAuthenticationAndInsideWorker(){
        for(boolean worker:new boolean[]{false,true}){
            var g=new Graph();var before=g.local.state();var expiry=g.original.enrollment().sessionExpiresAt();
            if(!worker){g.local.at(expiry.minusNanos(1));g.local.afterAuthentication(()->g.local.at(expiry));refused(()->inspect(g,reader(g)));}
            else{var caller=Thread.currentThread();var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Thread.currentThread()==caller?g.local.now():expiry;}};refused(()->inspect(g,reader(g,g.sdk.clients,clock,()->0)));}
            assertThat(g.sdk.http.calls).isEmpty();noEffects(g,before);
        }
    }
    @ParameterizedTest @ValueSource(strings={"advancing","identity","frozen","deletes-after","closing-clock"})
    void sameAdmittedReadyClockMustBracketTheGraph(String bad){
        var g=new Graph();g.queueClocks();var before=g.local.state();g.change(bad.equals("closing-clock")?16:0,j->{switch(bad){case "advancing"->j.addProperty("status","advancing");case "identity","closing-clock"->j.addProperty("id","clock_other");case "frozen"->j.addProperty("frozen_time",4701);case "deletes-after"->j.addProperty("deletes_after",3001);default->throw new AssertionError(bad);}});
        refused(()->inspect(g,reader(g)));assertThat(g.sdk.http.calls).hasSize(bad.equals("closing-clock")?17:1);noEffects(g,before);
    }
    @Test void invocationBudgetStartsBeforeAuthenticationAndDoesNotRestartAtEachGet(){
        var g=new Graph();var nanos=new AtomicLong();var before=g.local.state();g.local.afterAuthentication(()->nanos.set(Duration.ofSeconds(30).toNanos()));
        refused(()->inspect(g,reader(g,g.sdk.clients,g.local.clock(),nanos::get)));assertThat(g.sdk.http.calls).isEmpty();noEffects(g,before);
        var second=new Graph();second.queueClocks();var n=new AtomicLong();var once=new AtomicBoolean();second.local.afterProof=()->{if(once.compareAndSet(false,true))n.addAndGet(Duration.ofSeconds(15).toNanos());};
        var transport=new HttpClient(){public StripeResponse request(StripeRequest r)throws com.stripe.exception.StripeException{var result=second.sdk.http.request(r);n.addAndGet(Duration.ofSeconds(1).toNanos());return result;}};
        var clients=factory(transport);var initial=second.local.state();refused(()->inspect(second,reader(second,clients,second.local.clock(),n::get)));assertThat(second.sdk.http.calls).hasSize(15);noEffects(second,initial);
    }
    @Test void actualThirtySecondInvocationBudgetIncludesBlockingOriginalAuthenticationWithStillClocks(){
        var g=new Graph();var before=g.local.state();var once=new AtomicBoolean();
        g.local.afterAuthentication(()->{if(once.compareAndSet(false,true))try{Thread.sleep(Duration.ofMillis(30100));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("interrupted inert authentication");}});
        var clients=new StripeClientFactory(){public StripeClient create(StripeCredentials ignored){throw new AssertionError("Expired invocation reached client creation");}};
        long started=System.nanoTime();refused(()->inspect(g,reader(g,clients,g.local.clock(),()->0)));
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(30),Duration.ofSeconds(40));
        assertThat(g.local.proofLocators).isEmpty();assertThat(g.local.stepLocators).isEmpty();assertThat(g.sdk.http.calls).isEmpty();noEffects(g,before);
    }
    @Test void arbitraryAuthenticationAndProofStoreFailuresAreCauseFreeBeforeSdk(){
        for(boolean proof:new boolean[]{false,true}){var g=new Graph();var before=g.local.state();Runnable failure=()->{throw new IllegalStateException("private authority/store material");};if(proof)g.local.afterProof=failure;else g.local.afterAuthentication(failure);refused(()->inspect(g,reader(g)));assertThat(g.sdk.http.calls).isEmpty();noEffects(g,before);}
    }
    @Test void originalProofDriftOnClosingClockCannotReturnAPreparedDescription(){
        var g=new Graph();g.queueClocks();var before=g.local.state();var count=new AtomicInteger();
        var transport=new HttpClient(){public StripeResponse request(StripeRequest r)throws com.stripe.exception.StripeException{var result=g.sdk.http.request(r);if(count.incrementAndGet()==17)g.local.proof=null;return result;}};
        refused(()->inspect(g,reader(g,factory(transport),g.local.clock(),()->0)));assertThat(g.sdk.http.calls).hasSize(17);noEffects(g,before);
    }
    @Test void utcDeadlineAndRawUtcRollbackFailEvenWhenInjectedMonotonicTimeStaysStill(){
        for(boolean rollback:new boolean[]{false,true}){var g=new Graph();g.local.at(g.local.now().plusSeconds(10));g.queueClocks();var before=g.local.state();var start=g.local.now();g.sdk.http.afterResponse=()->g.local.at(rollback?start.minusNanos(1):start.plusSeconds(30));refused(()->inspect(g,reader(g,g.sdk.clients,g.local.clock(),()->0)));assertThat(g.sdk.http.calls).hasSize(1);noEffects(g,before);}
    }
    @Test void injectedPerGetDeadlineAndMonotonicRollbackAreBothExclusive(){
        for(long delta:new long[]{Duration.ofSeconds(2).toNanos(),-1}){var g=new Graph();g.queueClocks();var n=new AtomicLong(10);var before=g.local.state();g.sdk.http.afterResponse=()->n.addAndGet(delta);refused(()->inspect(g,reader(g,g.sdk.clients,g.local.clock(),n::get)));assertThat(g.sdk.http.calls).hasSize(1);noEffects(g,before);}
    }
    @Test void utcPerGetDeadlineCannotBeExtendedByStillInjectedMonotonicClock(){var g=new Graph();g.queueClocks();var before=g.local.state();var start=g.local.now();g.sdk.http.afterResponse=()->g.local.at(start.plusSeconds(2));refused(()->inspect(g,reader(g,g.sdk.clients,g.local.clock(),()->0)));assertThat(g.sdk.http.calls).hasSize(1);noEffects(g,before);}
    @Test void blockedSdkGetUsesActualTwoSecondBoundCancelsAndNeverRetries()throws Exception{
        var g=new Graph();var before=g.local.state();var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var entries=new AtomicInteger();
        var transport=new HttpClient(){public StripeResponse request(StripeRequest request){entries.incrementAndGet();entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){cancelled.countDown();throw new IllegalStateException("private provider detail");}}};
        long start=System.nanoTime();refused(()->inspect(g,reader(g,factory(transport),g.local.clock(),()->0)));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(entries).hasValue(1);noEffects(g,before);
    }
    @Test void callerInterruptBeforeOrDuringReadIsPreservedAndCancelsWorker()throws Exception{
        var g=new Graph();Thread.currentThread().interrupt();try{refused(()->inspect(g,reader(g)));assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(g.sdk.http.calls).isEmpty();}finally{Thread.interrupted();}
        var second=new Graph();var before=second.local.state();var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var flag=new AtomicBoolean();
        var transport=new HttpClient(){public StripeResponse request(StripeRequest request){entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){cancelled.countDown();throw new IllegalStateException("private provider detail");}}};
        var reader=reader(second,factory(transport),second.local.clock(),()->0);var task=new FutureTask<Void>(()->{try{refused(()->inspect(second,reader));flag.set(Thread.currentThread().isInterrupted());return null;}finally{Thread.interrupted();}});var caller=Thread.ofVirtual().start(task);
        try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();caller.interrupt();task.get(1,TimeUnit.SECONDS);assertThat(flag).isTrue();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();noEffects(second,before);}finally{caller.interrupt();task.cancel(true);}
    }
    @Test void sdkExceptionAndPageBoundsStaySafeWithoutStopsOrMutations(){
        var g=new Graph();var before=g.local.state();g.sdk.http.disconnect();refused(()->inspect(g,reader(g)));assertThat(g.sdk.http.calls).hasSize(1);noEffects(g,before);
        var second=new Graph();second.queueClocks();var initial=second.local.state();var original=new ArrayList<>(second.sdk.http.replies);second.sdk.http.replies.clear();second.sdk.http.replies.addAll(original.subList(0,3));
        for(int i=0;i<11;i++)second.sdk.http.reply("{\"object\":\"list\",\"has_more\":true,\"data\":[{\"id\":\"in_page"+i+"\",\"object\":\"invoice\"}]}");
        refused(()->inspect(second,reader(second)));assertThat(second.sdk.http.calls).hasSize(13).hasSizeLessThanOrEqualTo(100);noEffects(second,initial);
    }
    static StripeClientFactory factory(HttpClient transport){return new StripeClientFactory(){public StripeClient create(StripeCredentials ignored){return StripeClient.builder().setApiKey("sk_test_INERT").setHttpClient(transport).build();}};}
}
