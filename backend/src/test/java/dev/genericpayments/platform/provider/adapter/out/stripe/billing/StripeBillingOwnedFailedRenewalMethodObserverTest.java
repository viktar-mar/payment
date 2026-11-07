package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.net.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real descriptive readers and official inert SDK; only the durable service boundary is substituted.
 * Genuine store/service/FactService composition is separately hosted, never selected offline. */
class StripeBillingOwnedFailedRenewalMethodObserverTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void returnsOnlyPersistedReceiptAndPublishesRawDescriptor(boolean recovered) {
        var h=new Harness(recovered);h.queue();assertThat(h.observe()).isSameAs(recovered?h.publication:h.completion);
        assertThat(h.g.f.f.http.calls).hasSize(recovered?15:17).allSatisfy(r->{
            assertThat(r.method().name()).isEqualTo("GET");assertThat(r.options().getMaxNetworkRetries()).isZero();
            assertThat(r.options().getReadTimeout()).isBetween(1,2000);assertThat(r.options().getConnectTimeout()).isBetween(1,2000);
            assertThat(r.headers().firstValue("Stripe-Version")).contains("2026-09-30.endive");});
        assertThat(h.g.f.f.http.replies).isEmpty();assertThat(h.ioOnCaller).isFalse();
        assertThat(h.inspections).isEqualTo(2*(recovered?15:17)+2);verify(h.service,never()).stop(any(),any());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void changedClaimAndSampleCannotStartIo(boolean recovered) {
        for(var bad:List.of("phase","reservation","invocation","future","old","sample","fence")) {
            var h=new Harness(recovered);h.claimMutation=bad;safe(h);assertThat(h.g.f.f.http.calls).isEmpty();
            verify(h.service).stop(h.reservation,OwnedFailedRenewal.Reason.AUTHORITY_DRIFT);
        }
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,36})
    void callerContextDriftIsCheckedBeforeAfterAndFinally(int inspection) {
        var h=new Harness(false);h.queue();h.driftAt=inspection;safe(h);
        assertThat(h.g.f.f.http.calls).hasSize(inspection<3?0:inspection==3?1:17);
        verify(h.service).stop(h.reservation,OwnedFailedRenewal.Reason.AUTHORITY_DRIFT);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exactClockRequiredAtBothEnds(boolean recovered) {
        for(int index:new int[]{0,recovered?14:16}) {
            var h=new Harness(recovered);h.queue();var q=new ArrayList<>(h.g.f.f.http.replies);
            var j=dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json(((StripeResponse)q.get(index)).body());j.addProperty("name","foreign");
            q.set(index,new StripeResponse(200,HttpHeaders.of(Map.of()),j.toString()));h.g.f.f.http.replies.clear();h.g.f.f.http.replies.addAll(q);
            safe(h);assertThat(h.g.f.f.http.calls).hasSize(index+1);verify(h.service).stop(h.reservation,OwnedFailedRenewal.Reason.UNCERTAIN);
        }
    }
    @ParameterizedTest @ValueSource(strings={"utc","total","backwards","call","lock","tiny"})
    void sharedBudgetIncludesContextWorkAndEveryCompleteCall(String bad) {
        var h=new Harness(false);h.queue();
        if(bad.equals("lock"))h.onInspect=()->h.nanos=Duration.ofSeconds(30).toNanos();
        else if(bad.equals("tiny"))h.onInspect=()->h.at=h.at.plusSeconds(30).minusNanos(500_000);
        else h.g.f.f.http.afterResponse=()->{if(bad.equals("utc"))h.at=h.at.plusSeconds(30);else h.nanos=bad.equals("backwards")?-1:Duration.ofSeconds(bad.equals("call")?2:30).toNanos();};
        safe(h);assertThat(h.g.f.f.http.calls).hasSize(Set.of("lock","tiny").contains(bad)?0:1);
    }
    @Test void schedulingDelayConsumesBudgetBeforeTransportStarts() {
        var h=new Harness(false);h.queue();h.workerExpired=true;safe(h);assertThat(h.g.f.f.http.calls).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints={83,84})
    void hundredthGetIsAllowedButTrailingClockAsHundredAndFirstCannotExecute(int extras) {
        var h=new Harness(false);h.queue();var queue=new ArrayList<>(h.g.f.f.http.replies);var clock=queue.getFirst();var trailing=queue.getLast();
        // Exercise the exact outer/mapper executor, without requiring an invalid 100-object billing graph.
        for(int i=0;i<extras;i++)queue.add(1,clock);h.g.f.f.http.replies.clear();h.g.f.f.http.replies.addAll(queue);
        try(var mapper=mockStatic(StripeBillingSnapshotMapper.class,CALLS_REAL_METHODS)) {
            mapper.when(()->StripeBillingSnapshotMapper.retrieveOwnedMethodReady(any(),any(),any(),any(),any(),any(),any(),any()))
                .thenAnswer(call->{StripeBillingSnapshotMapper.ReadExecutor reads=call.getArgument(7);
                    var client=h.g.f.f.clients.create(h.g.f.f.credentials.resolve(h.g.f.f.account));
                    for(int i=0;i<extras;i++)reads.execute(()->client.v1().testHelpers().testClocks().retrieve("clock_owned",reads.options()));
                    return call.callRealMethod();});
            if(extras==84)safe(h);else {assertThat(h.observe()).isSameAs(h.completion);verify(h.service,never()).stop(any(),any());}
        }
        assertThat(h.g.f.f.http.calls).hasSize(100);assertThat(h.g.f.f.http.calls.getLast().url().getPath()).isEqualTo(extras==84?"/v1/payment_methods/pm_saved":"/v1/test_helpers/test_clocks/clock_owned");
        assertThat(h.g.f.f.http.calls.subList(0,extras+1)).allSatisfy(r->assertThat(r.url().getPath()).isEqualTo("/v1/test_helpers/test_clocks/clock_owned"));
        if(extras==84){assertThat(h.g.f.f.http.replies).containsExactly(trailing);verify(h.service).stop(h.reservation,OwnedFailedRenewal.Reason.UNCERTAIN);}
        else assertThat(h.g.f.f.http.replies).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void serviceOwnedFailureDoesNotStopTwiceAndRedactsCause(boolean begin) {
        var h=new Harness(false);h.queue();var error=new IllegalStateException("SECRET",new Exception("SECRET"));error.addSuppressed(new Exception("SECRET"));
        if(begin)when(h.service.beginMethodRead(any(),any())).thenThrow(error);
        else when(h.service.publishMethod(any(),any(),any(),any())).thenThrow(error);
        var failure=catchThrowable(h::observe);assertSafe(failure);assertThat(failure.getSuppressed()).singleElement().satisfies(e->assertThat(e).hasMessage("Owned method stop unavailable").hasNoCause());
        verify(h.service,never()).stop(any(),any());assertThat(h.g.f.f.http.calls).hasSize(begin?0:17);
    }
    @Test void stopAndTransportFailuresHaveOnlyFixedDiagnostics() {
        var h=new Harness(false);h.g.f.f.http.disconnect();doThrow(new IllegalStateException("SECRET")).when(h.service).stop(any(),any());
        var failure=catchThrowable(h::observe);assertSafe(failure);assertThat(failure.getSuppressed()).singleElement().satisfies(e->assertThat(e).hasMessage("Owned method stop unavailable").hasNoCause());
        assertThat(h.g.f.f.http.calls).hasSize(1);verify(h.service).stop(h.reservation,OwnedFailedRenewal.Reason.UNCERTAIN);
    }
    @Test void programmingErrorPropagatesAndSuccessfulCommitHasNoRetroactiveTimeout() {
        var broken=new Harness(false);assertThatThrownBy(broken::observe).isInstanceOf(AssertionError.class);
        var h=new Harness(false);h.queue();h.commitDelay=true;assertThat(h.observe()).isSameAs(h.completion);verify(h.service,never()).stop(any(),any());
    }
    @Test void realTimeoutCancelsSdkWorkerWithFrozenClocks() throws Exception {
        var h=new Harness(false);h.blocker=new BlockingTransport();long started=System.nanoTime();safe(h);
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(1),Duration.ofSeconds(5));
        assertThat(h.blocker.stopped.await(1,TimeUnit.SECONDS)).isTrue();assertThat(h.blocker.interrupted.get()).isTrue();
    }
    @Test void interruptionPreservesCallerFlagAndCancelsSdk() throws Exception {
        var h=new Harness(true);h.blocker=new BlockingTransport();var preserved=new AtomicBoolean();
        var task=new FutureTask<Throwable>(()->{var e=catchThrowable(h::observe);preserved.set(Thread.currentThread().isInterrupted());return e;});
        var caller=Thread.ofPlatform().start(task);
        try {assertThat(h.blocker.entered.await(3,TimeUnit.SECONDS)).isTrue();caller.interrupt();assertSafe(task.get(3,TimeUnit.SECONDS));}
        finally {caller.interrupt();}
        assertThat(preserved.get()).isTrue();assertThat(h.blocker.stopped.await(1,TimeUnit.SECONDS)).isTrue();verify(h.service).stop(h.reservation,OwnedFailedRenewal.Reason.UNCERTAIN);
    }
    private static void safe(Harness h) {assertSafe(catchThrowable(h::observe));verify(h.service,times(1)).stop(eq(h.reservation),any());verify(h.service,never()).publishMethod(any(),any(),any(),any());verify(h.service,never()).publishRecovery(any(),any(),any(),any());}
    private static void assertSafe(Throwable e) {assertThat(e).isInstanceOfSatisfying(PaymentApplicationException.class,x->{assertThat(x.code()).isEqualTo("OWNED_FAILED_RENEWAL_METHOD_UNAVAILABLE");assertThat(x.status()).isEqualTo(409);assertThat(x).hasMessage("Owned method observation unavailable").hasNoCause();});}
    private static final class BlockingTransport extends HttpClient {
        final CountDownLatch entered=new CountDownLatch(1),stopped=new CountDownLatch(1);final AtomicBoolean interrupted=new AtomicBoolean();
        public StripeResponse request(StripeRequest r) throws com.stripe.exception.StripeException {entered.countDown();try {new CountDownLatch(1).await(10,TimeUnit.SECONDS);throw new AssertionError("not cancelled");}catch(InterruptedException e){interrupted.set(true);throw new com.stripe.exception.ApiConnectionException("SECRET");}finally {stopped.countDown();}}
    }
    static final class Harness {
        final StripeBillingOwnedRecoveryMappingTest.Graph cash=new StripeBillingOwnedRecoveryMappingTest.Graph();
        final StripeBillingOwnedMethodMappingTest.Graph g=cash.debt;
        final OwnedFailedRenewalMethodService service=mock(OwnedFailedRenewalMethodService.class);
        final boolean recovered;final Thread caller=Thread.currentThread();
        final SubscriptionBillingTimePort.Sample sample=new SubscriptionBillingTimePort.Sample(Instant.ofEpochSecond(g.admission.ready().ack().advance().target()),SubscriptionBillingTimePort.Mode.ISOLATED);
        final Reservation reservation=new Reservation(UUID.randomUUID(),g.admission,g.failure,g.candidate.operation().id(),g.candidate.operation().fingerprint(),0,g.f.binding(),sample,g.failureAt.plusSeconds(1));
        final Completion completion=new Completion(reservation.id(),UUID.randomUUID(),reservation.setupOperationId(),2,g.candidate.setupStep().id(),2,g.candidate.methodStep().id(),2,"seti_saved","pm_saved",3,3,g.at);
        final Recovery recovery=new Recovery(reservation.id(),cash.recovery,3);
        final Publication publication=new Publication(reservation.id(),new OwnedFailedRenewal.Publication(g.admission.id(),UUID.randomUUID(),cash.operation.id(),g.failure.billId(),UUID.randomUUID(),4,g.at),4);
        final MethodReadContext method=new MethodReadContext(g.f.f.account,g.f.local,g.f.binding(),g.candidate.operation(),g.candidate.setup(),g.candidate.setupStep(),g.candidate.methodStep(),sample);
        final RecoveredReadContext context=new RecoveredReadContext(g.f.f.account,g.f.local,g.f.binding(),cash.operation,completion,recovery,sample);
        Instant at=g.at;long nanos;int inspections,driftAt;String claimMutation="";Runnable onInspect;boolean ioOnCaller,workerExpired,commitDelay;BlockingTransport blocker;
        final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return at;}};
        Harness(boolean recovered) {
            this.recovered=recovered;
            when(service.beginMethodRead(eq(reservation),any())).thenAnswer(i->claim(i.getArgument(1)));
            when(service.beginRecoveredRead(eq(reservation),eq(recovery),any())).thenAnswer(i->claim(i.getArgument(2)));
            when(service.inspectMethodRead(eq(reservation),any())).thenAnswer(i->{inspect();return method;});
            when(service.inspectRecoveredRead(eq(reservation),any())).thenAnswer(i->{inspect();return context;});
            when(service.publishMethod(eq(reservation),any(),any(),eq(method))).thenAnswer(i->{MethodEvidence e=i.getArgument(2);assertThat(e.snapshot().operationKey()).isNull();assertThat(e.snapshot().operationObservation()).isNull();assertThat(e.snapshot().paymentMethodReady()).isTrue();assertThat(e.setupIntent()).isEqualTo("seti_saved");delay();return completion;});
            when(service.publishRecovery(eq(reservation),any(),any(),eq(context))).thenAnswer(i->{OwnedFailedRenewal.RecoveredEvidence e=i.getArgument(2);assertThat(e.snapshot().operationKey()).isNull();assertThat(e.snapshot().operationObservation()).isNull();assertThat(e.snapshot().paymentMethodReady()).isFalse();delay();return publication;});
        }
        void delay(){if(commitDelay){at=at.plusSeconds(60);nanos=Duration.ofMinutes(1).toNanos();}}
        void inspect(){assertThat(Thread.currentThread().isVirtual()).isFalse();inspections++;if(onInspect!=null){var x=onInspect;onInspect=null;x.run();}if(inspections==driftAt)throw new IllegalStateException("SECRET context");}
        ReadClaim claim(UUID invocation) {var start=at.plusSeconds(claimMutation.equals("future")?1:claimMutation.equals("old")?-1:0);var op=recovered?cash.operation:g.candidate.operation();
            return new ReadClaim(UUID.randomUUID(),claimMutation.equals("reservation")?UUID.randomUUID():reservation.id(),claimMutation.equals("phase")?(recovered?Phase.METHOD:Phase.RECOVERED):(recovered?Phase.RECOVERED:Phase.METHOD),claimMutation.equals("invocation")?UUID.randomUUID():invocation,start,start.plusSeconds(30),new Fence(claimMutation.equals("fence")?99:3,op.id(),op.version(),null,null,recovered?3L:2L),claimMutation.equals("sample")?new SubscriptionBillingTimePort.Sample(sample.billingAt().plusSeconds(1),sample.mode()):sample);}
        void queue(){if(recovered)cash.queue();else g.queue();var j=dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json(g.f.f.clockJson());j.addProperty("frozen_time",g.admission.ready().observation().frozenTime());String clock=j.toString();g.f.f.http.replies.addFirst(new StripeResponse(200,HttpHeaders.of(Map.of()),clock));g.f.f.http.reply(clock);g.f.f.http.afterResponse=new Runnable(){public void run(){ioOnCaller|=Thread.currentThread()==caller;g.f.f.http.afterResponse=this;}};}
        Object observe(){var clients=g.f.f.clients;if(blocker!=null)clients=new dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory(){@Override public com.stripe.StripeClient create(dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentials c){return com.stripe.StripeClient.builder().setApiKey("sk_test_INERT").setHttpClient(blocker).build();}};
            var observer=new StripeBillingOwnedFailedRenewalMethodObserver(service,g.f.f.credentials,clients,g.f.f.digest,clock,()->workerExpired&&Thread.currentThread().isVirtual()?Duration.ofSeconds(31).toNanos():nanos);return recovered?observer.observeRecovery(reservation,recovery):observer.observeMethod(reservation);}
    }
}
