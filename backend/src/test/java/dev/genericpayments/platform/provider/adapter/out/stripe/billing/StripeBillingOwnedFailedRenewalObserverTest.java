package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.net.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.service.*;
import dev.genericpayments.platform.provider.adapter.out.persistence.OwnedFailedRenewalFactProof;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalMappingTest.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFailedRenewalMappingTest.*;
import static dev.genericpayments.platform.support.StripeBillingInertSdkFixture.port;
import static org.assertj.core.api.Assertions.*;

/** Real service, fact service and domain projection; persistence/transactions and SDK IO are handwritten memory ports.
 * Original settled bill is a fixture precondition, not claimed to be genuine initial-payment provenance. */
class StripeBillingOwnedFailedRenewalObserverTest {
    private static final List<String> READ_PATHS=List.of(
        "/v1/test_helpers/test_clocks/clock_owned","/v1/customers/cus_owned","/v1/subscriptions/sub_owned","/v1/invoices",
        "/v1/invoices/in_owned/lines","/v1/invoice_payments","/v1/payment_intents/pi_owned","/v1/charges/ch_owned",
        "/v1/invoice_payments","/v1/invoices/in_renewal/lines","/v1/invoice_payments","/v1/payment_intents/pi_renewal",
        "/v1/charges/ch_renewal","/v1/invoice_payments","/v1/test_helpers/test_clocks/clock_owned");
    @Test void returnsPersistedDebtReceiptThroughFifteenCompleteGets() {
        var h=new Harness();h.graph();var result=h.observer().observe(h.admission);
        assertThat(result).isSameAs(h.failure);assertThat(h.f.local.debt().billingRecordId()).isEqualTo(result.billId());
        assertThat(h.f.local.billingStatus()).isEqualTo(Subscription.Status.PAST_DUE);
        assertThat(h.bills.get("in_renewal").settlement()).isEqualTo(BillingRecord.Settlement.OPEN);
        assertThat(h.cash).containsExactly("pi_owned");assertThat(h.operations).hasSize(1);
        assertThat(h.events).contains(SubscriptionEvent.Type.RENEWAL_FAILED);assertThat(h.callbacks).isPositive();
        assertThat(h.stops).isZero();assertThat(h.claims).isEqualTo(1);assertThat(h.f.f.http.replies).isEmpty();
        assertThat(h.f.f.http.calls).hasSize(15);assertPrefix(h);
        assertThat(h.f.f.http.calls).allSatisfy(r->{assertThat(r.method().name()).isEqualTo("GET");
            assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.options().getReadTimeout()).isBetween(1,2000);
            assertThat(r.options().getConnectTimeout()).isBetween(1,2000);
            assertThat(r.headers().firstValue("Stripe-Version")).contains("2026-09-30.endive");});
        assertThat(h.ioInTransaction).isFalse();assertThat(h.ioOnCaller).isFalse();
    }
    @Test void inertTransportProgrammingErrorCannotMasqueradeAsSafeRefusal() {
        var h=new Harness();
        assertThatThrownBy(()->h.observer().observe(h.admission)).isInstanceOf(AssertionError.class).hasMessage("Unplanned inert SDK request");
    }
    @ParameterizedTest @ValueSource(strings={"missing","foreign","invocation","future","old","fence","phase"})
    void rejectsReturnedClaimBeforeAnyProviderRead(String mutation) {
        var h=new Harness();h.claimTransform=r->switch(mutation){
            case "missing"->null;
            case "foreign"->new ReadClaim(r.id(),UUID.randomUUID(),r.phase(),r.invocation(),r.startedAt(),r.deadlineAt(),r.fence());
            case "invocation"->new ReadClaim(r.id(),r.admissionId(),r.phase(),UUID.randomUUID(),r.startedAt(),r.deadlineAt(),r.fence());
            case "phase"->new ReadClaim(r.id(),r.admissionId(),Phase.RECOVERED,r.invocation(),r.startedAt(),r.deadlineAt(),new Fence(r.fence().revision(),UUID.randomUUID(),0L,null,null,0L));
            case "future","old"->{var start=r.startedAt().plusSeconds(mutation.equals("future")?1:-1);yield new ReadClaim(r.id(),r.admissionId(),r.phase(),r.invocation(),start,start.plusSeconds(30),r.fence());}
            default->new ReadClaim(r.id(),r.admissionId(),r.phase(),r.invocation(),r.startedAt(),r.deadlineAt(),new Fence(99,null,null,null,null,0L));};
        safeFailure(h);assertThat(h.f.f.http.calls).isEmpty();assertThat(h.claims).isEqualTo(1);assertThat(h.reason).isEqualTo(Reason.AUTHORITY_DRIFT);
    }
    @ParameterizedTest @ValueSource(strings={"binding","fence","time","subscription","account"})
    void contextDriftAfterCompleteSdkReadStopsOnceAfterUnwind(String mutation) {
        var h=new Harness();h.graph();h.f.f.http.afterResponse=()->{switch(mutation){
            case "binding"->h.changeBinding();case "fence"->h.sequence++;case "time"->h.billingDrift=true;
            case "account"->h.accountMissing=true;default->{var s=h.f.local;h.f.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision()+1,s.createdAt(),s.updatedAt(),s.lastReconciledAt());}}
        };
        safeFailure(h);assertThat(h.f.f.http.calls).hasSize(1);assertThat(h.reason).isEqualTo(Reason.AUTHORITY_DRIFT);
        assertThat(h.trace).endsWith("begin","stop","commit");
    }
    @ParameterizedTest @ValueSource(ints={1,2,32,33})
    void bindingDriftAtInitialBeforeFinalAndPublicationInspectionHasNoEffects(int at) {
        var h=new Harness();h.graph();h.inspectionAt=at;h.inspectionEffect=at==1?()->h.binding=null:h::changeBinding;
        safeFailure(h);assertThat(h.f.f.http.calls).hasSize(at<3?0:15);assertThat(h.reason).isEqualTo(Reason.AUTHORITY_DRIFT);
    }
    @ParameterizedTest @ValueSource(strings={"id","object","live","deleted","name","frozen","created","deletes","status","advancing"})
    void exactReadyClockIsRequiredBeforeAndAfterGraph(String mutation) {
        for(int index:new int[]{0,14}) {
            var h=new Harness();h.graph();h.replace(index,j->{switch(mutation){
                case "id"->j.addProperty("id","clock_other");case "object"->j.addProperty("object","other");
                case "live"->j.addProperty("livemode",true);case "deleted"->j.addProperty("deleted",true);
                case "name"->j.addProperty("name","other");case "frozen"->j.addProperty("frozen_time",1);
                case "created"->j.addProperty("created",1);case "deletes"->j.addProperty("deletes_after",1);
                case "status"->j.addProperty("status","advancing");default->j.add("status_details",com.google.gson.JsonParser.parseString("{\"advancing\":{\"target_frozen_time\":1}}"));}});
            safeFailure(h);assertThat(h.f.f.http.calls).hasSize(index+1);assertThat(h.reason).isEqualTo(Reason.UNCERTAIN);
        }
    }
    @ParameterizedTest @ValueSource(strings={"transport","http","malformed","graph"})
    void providerFailureIsSafeAndNeverRetries(String mutation) {
        var h=new Harness();h.graph();
        if(mutation.equals("graph"))h.replace(2,j->j.addProperty("status","active"));
        else {h.f.f.http.replies.clear();if(mutation.equals("transport"))h.f.f.http.disconnect();
            else if(mutation.equals("http"))h.f.f.http.error(500);else h.f.f.http.reply("{SECRET malformed");}
        safeFailure(h);assertThat(h.f.f.http.calls).hasSize(mutation.equals("graph")?3:1);assertThat(h.reason).isEqualTo(Reason.UNCERTAIN);
    }
    @ParameterizedTest @ValueSource(strings={"utc","monotonic","backwards","lock","short-call"})
    void oneSharedBudgetCannotBeExtendedByProviderOrDatabaseWork(String mutation) {
        var h=new Harness();h.graph();
        if(mutation.equals("lock")){h.inspectionEffect=()->h.nanos=Duration.ofSeconds(30).toNanos();}
        else h.f.f.http.afterResponse=()->{if(mutation.equals("utc"))h.utc=h.utc.plusSeconds(30);
            else h.nanos=mutation.equals("backwards")?-1:Duration.ofSeconds(mutation.equals("short-call")?2:30).toNanos();};
        safeFailure(h);assertThat(h.f.f.http.calls).hasSize(mutation.equals("lock")?0:1);
    }
    @Test void pageLimitRefusesBeforeAnEleventhInvoicePage() {
        var h=new Harness();h.graph();var replies=new ArrayList<>(h.f.f.http.replies);h.f.f.http.replies.clear();
        for(int i=0;i<3;i++)h.f.f.http.replies.add(replies.get(i));
        for(int i=0;i<10;i++){var j=StripeBillingOwnedSubscriptionFixture.json(((StripeResponse)replies.get(3)).body());
            j.addProperty("has_more",true);j.getAsJsonArray("data").remove(1);j.getAsJsonArray("data").get(0).getAsJsonObject().addProperty("id","in_page"+i);h.f.f.http.reply(j.toString());}
        assertSafe(catchThrowable(()->h.observer().observe(h.admission)));assertThat(h.stops).isEqualTo(1);assertThat(h.failure).isNull();
        assertThat(h.f.f.http.calls).hasSize(13);assertThat(h.f.f.http.calls.subList(3,13)).allSatisfy(r->assertThat(r.url().getPath()).isEqualTo("/v1/invoices"));
        assertThat(h.f.f.http.replies).isEmpty();
    }
    @Test void publicationRollbackAndStopFailureExposeOnlyFixedDiagnostics() {
        var h=new Harness();h.graph();h.failReceipt=true;safeFailure(h);
        assertThat(h.trace).containsSubsequence("rollback","begin","stop","commit");
        var broken=new Harness();broken.graph();broken.binding=null;broken.failStop=true;
        var error=catchThrowable(()->broken.observer().observe(broken.admission));assertSafe(error);
        assertThat(error.getSuppressed()).singleElement().satisfies(e->{assertThat(e).hasMessage("Failed renewal stop unavailable").hasNoCause();});
        assertThat(broken.stops).isEqualTo(1);assertThat(broken.f.f.http.calls).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void serviceOwnedBeginOrPublicationStopFailureIsSanitizedWithoutSecondStop(boolean atBegin) {
        var h=new Harness();h.graph();h.failStop=true;
        if(atBegin)h.accountMissing=true;else h.failReceipt=true;
        var error=catchThrowable(()->h.observer().observe(h.admission));assertSafe(error);
        assertThat(error.getSuppressed()).singleElement().satisfies(e->assertThat(e).hasMessage("Failed renewal stop unavailable").hasNoCause());
        assertThat(h.stops).isEqualTo(1);assertThat(h.f.f.http.calls).hasSize(atBegin?0:15);assertThat(h.failure).isNull();
    }
    @Test void successfulCommitIsReturnedEvenWhenCommitConsumesRemainingBudget() {
        var h=new Harness();h.graph();h.afterCommit=()->{h.utc=h.utc.plusSeconds(60);h.nanos=Duration.ofMinutes(1).toNanos();};
        assertThat(h.observer().observe(h.admission)).isSameAs(h.failure);assertThat(h.stops).isZero();
    }
    @Test void remainingPersistedUtcAllowanceConstrainsEverySdkTimeout() {
        var h=new Harness();h.graph();h.inspectionEffect=()->h.utc=h.utc.plusSeconds(29);
        assertThat(h.observer().observe(h.admission)).isSameAs(h.failure);
        assertThat(h.f.f.http.calls).hasSize(15).allSatisfy(r->assertThat(r.options().getReadTimeout()).isBetween(1,1000));
    }
    @Test void subMillisecondAllowanceAndWorkerStartAfterDeadlineCannotStartIo() {
        var tiny=new Harness();tiny.graph();tiny.inspectionEffect=()->tiny.utc=tiny.utc.plusSeconds(30).minusNanos(500_000);
        safeFailure(tiny);assertThat(tiny.f.f.http.calls).isEmpty();
        var delayed=new Harness();delayed.graph();
        var observer=new StripeBillingOwnedFailedRenewalObserver(delayed.service,delayed.f.f.credentials,delayed.f.f.clients,delayed.clock,
            ()->Thread.currentThread().isVirtual()?Duration.ofSeconds(31).toNanos():0);
        assertSafe(catchThrowable(()->observer.observe(delayed.admission)));assertThat(delayed.f.f.http.calls).isEmpty();assertThat(delayed.stops).isEqualTo(1);
    }
    @Test void actualCompleteSdkTimeoutCancelsWorkerEvenWithFrozenInjectedClocks() throws Exception {
        var h=new Harness();var blocker=new BlockingTransport();long start=System.nanoTime();
        assertSafe(catchThrowable(()->h.observer(blocker).observe(h.admission)));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofSeconds(1),Duration.ofSeconds(5));
        assertThat(blocker.stopped.await(1,TimeUnit.SECONDS)).isTrue();assertThat(blocker.interrupted.get()).isTrue();
        assertThat(h.stops).isEqualTo(1);assertThat(h.reason).isEqualTo(Reason.UNCERTAIN);assertThat(h.failure).isNull();
    }
    @Test void interruptionCancelsWorkerAndPreservesCallerStatusWhileStoppingOnce() throws Exception {
        var h=new Harness();var blocker=new BlockingTransport();var preserved=new AtomicBoolean();
        var result=new FutureTask<Throwable>(()->{var error=catchThrowable(()->h.observer(blocker).observe(h.admission));preserved.set(Thread.currentThread().isInterrupted());return error;});
        var caller=Thread.ofPlatform().start(result);
        try {assertThat(blocker.entered.await(3,TimeUnit.SECONDS)).isTrue();caller.interrupt();assertSafe(result.get(3,TimeUnit.SECONDS));}
        finally {caller.interrupt();}
        assertThat(preserved.get()).isTrue();assertThat(blocker.stopped.await(1,TimeUnit.SECONDS)).isTrue();assertThat(blocker.interrupted.get()).isTrue();
        assertThat(h.stops).isEqualTo(1);assertThat(h.failure).isNull();
    }
    @Test void alreadyInterruptedCallerDoesNotStartProviderIo() {
        var h=new Harness();Thread.currentThread().interrupt();
        try {safeFailure(h);assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(h.f.f.http.calls).isEmpty();}
        finally {Thread.interrupted();}
    }
    private static final class BlockingTransport extends HttpClient {
        final CountDownLatch entered=new CountDownLatch(1),stopped=new CountDownLatch(1);
        final AtomicBoolean interrupted=new AtomicBoolean();
        public StripeResponse request(StripeRequest request) throws com.stripe.exception.StripeException {
            entered.countDown();
            try {new CountDownLatch(1).await(10,TimeUnit.SECONDS);throw new AssertionError("Worker was not cancelled");}
            catch(InterruptedException e){interrupted.set(true);throw new com.stripe.exception.ApiConnectionException("SECRET interrupted transport");}
            finally {stopped.countDown();}
        }
    }
    private static void safeFailure(Harness h) {
        assertSafe(catchThrowable(()->h.observer().observe(h.admission)));
        assertThat(h.stops).isEqualTo(1);assertThat(h.failure).isNull();assertThat(h.f.local.debt()).isNull();
        assertThat(h.cash).containsExactly("pi_owned");assertThat(h.operations).hasSize(1);
        assertPrefix(h);
    }
    private static void assertPrefix(Harness h) {
        var calls=h.f.f.http.calls;assertThat(calls).hasSizeLessThanOrEqualTo(15).allSatisfy(r->assertThat(r.method().name()).isEqualTo("GET"));
        assertThat(calls.stream().map(r->r.url().getPath()).toList()).isEqualTo(READ_PATHS.subList(0,calls.size()));
    }
    private static void assertSafe(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(PaymentApplicationException.class,e->{assertThat(e.code()).isEqualTo("FAILED_RENEWAL_UNAVAILABLE");
            assertThat(e.status()).isEqualTo(409);assertThat(e).hasMessage("Failed renewal observation unavailable").hasNoCause();});
    }

    static final class Harness {
        final StripeBillingOwnedSubscriptionFixture f=paidLocal();
        final Admission admission=failed(admission(f),boundary(admission(f),f));
        final Map<UUID,SubscriptionOperation> operations=new LinkedHashMap<>();
        Map<String,BillingRecord> bills=new LinkedHashMap<>();
        Set<String> cash=new LinkedHashSet<>(Set.of("pi_owned"));
        List<SubscriptionEvent.Type> events=new ArrayList<>();
        final List<String> trace=new ArrayList<>();
        final Thread caller=Thread.currentThread();
        Instant utc=f.utc.plusSeconds(20);long nanos,sequence;int claims,stops,callbacks,inspections,inspectionAt=1;boolean active,ioInTransaction,ioOnCaller,failReceipt,failStop,billingDrift,accountMissing;
        Reason reason;ReadClaim claim;Failure failure;Runnable afterCommit,inspectionEffect;UnaryOperator<ReadClaim> claimTransform=UnaryOperator.identity();
        StripeBillingBindingPort.CustomerBinding binding=f.binding();
        final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return utc;}};
        final OwnedFailedRenewalService service;
        Harness() {
            var upstream=admission.firstRenewalAdmission();var intent=upstream.receipt().intent();var s=f.local;
            operations.put(intent.createOperation(),new SubscriptionOperation(intent.createOperation(),s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CREATE,
                SubscriptionOperation.Status.SUCCEEDED,"create","digest",intent.requestFingerprint(),intent.operationKey(),0,1,"sub_owned",null,utc,utc,1));
            bills.put("in_owned",new BillingRecord(upstream.bill(),s.siteId(),s.id(),s.paymentAccountId(),"in_owned",BillingRecord.Kind.INITIAL,s.billingPrice(),null,s.currentPeriod(),1000,"EUR",BillingRecord.Settlement.CONFIRMED_CASH,0,utc,utc));
            var ops=port(SubscriptionOperationStore.class,c->switch(c.name()){
                case "unresolved"->operations.values().stream().filter(o->o.status()!=SubscriptionOperation.Status.SUCCEEDED).toList();
                case "lock"->operations.get(c.args()[0]);case "find"->Optional.ofNullable(operations.get(c.args()[2]));
                default->throw new AssertionError(c.name());});
            var subs=port(SubscriptionStore.class,c->switch(c.name()){
                case "findOwned"->Optional.of(f.local);case "lock"->f.local;case "save"->{f.local=(Subscription)c.args()[0];yield f.local;}
                default->throw new AssertionError(c.name());});
            var records=port(BillingRecordStore.class,c->switch(c.name()){
                case "find"->Optional.ofNullable(bills.get(c.args()[3]));
                case "lock"->bills.values().stream().filter(b->b.id().equals(c.args()[1])).findFirst().orElseThrow();
                case "upsert"->{var b=(BillingRecord)c.args()[0];bills.put(b.providerInvoiceReference(),b);yield b;}
                default->throw new AssertionError(c.name());});
            var trials=port(TrialEligibilityStore.class,c->Optional.empty());
            var observations=port(SubscriptionObservationPort.class,c->switch(c.name()){
                case "captureFence"->sequence;case "consumeFence"->{assertThat(c.args()[1]).isEqualTo(sequence);sequence++;yield null;}
                default->throw new AssertionError(c.name());});
            var tx=new SubscriptionTransactionPort(){public boolean active(){return active;}public <T>T required(Supplier<T> w){return requiresNew(w);}
                public <T>T requiresNew(Supplier<T> w){assertThat(active).isFalse();active=true;trace.add("begin");
                    var old=f.local;var oldBills=new LinkedHashMap<>(bills);var oldCash=new LinkedHashSet<>(cash);var oldEvents=new ArrayList<>(events);var oldFailure=failure;long oldSequence=sequence;int oldCallbacks=callbacks;
                    try {var result=w.get();trace.add("commit");if(afterCommit!=null&&failure!=null){var effect=afterCommit;afterCommit=null;effect.run();}return result;}
                    catch(RuntimeException e){f.local=old;bills=oldBills;cash=oldCash;events=oldEvents;failure=oldFailure;sequence=oldSequence;callbacks=oldCallbacks;trace.add("rollback");throw e;}
                    finally {active=false;}}};
            var time=new SubscriptionBillingTimePort(){public Sample sample(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId a,dev.genericpayments.platform.sites.domain.SiteId s,dev.genericpayments.platform.customers.domain.CustomerId c,Instant at){return new Sample(Instant.ofEpochSecond(admission.ready().ack().advance().target()+(billingDrift?1:0)),Mode.ISOLATED);}
                public void requireUnscopedAllowed(dev.genericpayments.platform.sites.domain.SiteId s,dev.genericpayments.platform.customers.domain.CustomerId c){throw new AssertionError();}};
            var store=port(OwnedFailedRenewalStore.class,c->{assertThat(active).isTrue();return switch(c.name()){
                case "requireCurrentInTransaction"->{if(stops>0)throw new IllegalStateException("stopped");yield admission;}
                case "claimReadInTransaction"->{claims++;if(claim!=null)throw new IllegalStateException("duplicate");claim=new ReadClaim(UUID.randomUUID(),admission.id(),Phase.FAILED,(UUID)c.args()[2],utc,utc.plusSeconds(30),(Fence)c.args()[3]);yield claimTransform.apply(claim);}
                case "requireReadInTransaction"->{inspections++;if(inspectionEffect!=null&&inspections==inspectionAt){var effect=inspectionEffect;inspectionEffect=null;effect.run();}if(!Objects.equals(claim,c.args()[1]))throw new IllegalStateException("read");yield claim;}
                case "recordFailureInTransaction"->{if(failReceipt)throw new IllegalStateException("SECRET receipt");var b=bills.get("in_renewal");assertThat(f.local.debt()).isNotNull();
                    failure=new Failure(admission.id(),claim.id(),b.id(),b.version(),b.providerInvoiceReference(),"inpay_renewal","pi_renewal",b.period(),f.local.debt().graceDeadline(),f.local.revision(),sequence,utc);yield failure;}
                case "stopInTransaction"->{trace.add("stop");assertThat(active).isTrue();stops++;reason=(Reason)c.args()[1];if(failStop)throw new IllegalStateException("SECRET stop");yield null;}
                default->throw new AssertionError(c.name());};});
            var site=dev.genericpayments.platform.sites.domain.Site.create(s.siteId(),dev.genericpayments.platform.sites.domain.SiteKey.of("site"),"Site",s.paymentAccountId(),Set.of(java.net.URI.create("https://test.example")),Set.of(dev.genericpayments.platform.sites.domain.AuthenticationMode.SITE_TOKEN),dev.genericpayments.platform.sites.domain.PaymentMethodPolicy.automatic());
            var sitePort=port(dev.genericpayments.platform.sites.application.port.out.LockSitePort.class,c->Optional.of(site));
            var customers=port(dev.genericpayments.platform.customers.application.port.out.CustomerStore.class,c->Optional.of(dev.genericpayments.platform.customers.domain.Customer.create(s.customerId(),s.siteId(),null,null,utc)));
            var reads=new SubscriptionReadPreparation(ops,trials);
            var facts=new SubscriptionFactService(subs,ops,trials,sitePort,customers,id->Optional.of(f.f.account),records,
                c->{cash.add(c.providerPaymentReference());return null;},port(SubscriptionEventStore.class,c->{events.add(((SubscriptionEvent)c.args()[0]).type());return true;}),port(SubscriptionCatalogPort.class,c->f.terms),clock,
                (sub,event,access,enqueued)->callbacks++,port(SubscriptionObjectPort.class,c->null),(b,v,deadline)->new BillingMetadataPort.Captured(v,deadline),
                port(SubscriptionPolicyPort.class,c->new SubscriptionPolicy(s.siteId(),1,true,3,false,false,false,Set.of())),observations,value->"digest",
                port(SubscriptionQuoteStore.class,c->{throw new AssertionError(c.name());}),port(SubscriptionSweepPort.class,c->{throw new AssertionError(c.name());}),reads,
                port(SubscriptionAuthorityPort.class,c->{throw new AssertionError(c.name());}),time,new OwnedFailedRenewalFactProof(store,tx,time));
            service=new OwnedFailedRenewalService(store,tx,ops,(a,c)->{},id->accountMissing?Optional.empty():Optional.of(f.f.account),subs,records,reads,observations,facts,trials,time,clock,
                port(StripeBillingBindingPort.class,c->Optional.ofNullable(binding)));
        }
        StripeBillingOwnedFailedRenewalObserver observer(){return new StripeBillingOwnedFailedRenewalObserver(service,f.f.credentials,f.f.clients,clock,()->nanos);}
        StripeBillingOwnedFailedRenewalObserver observer(HttpClient transport){
            var clients=new dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory(){
                @Override public com.stripe.StripeClient create(dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentials ignored){
                    return com.stripe.StripeClient.builder().setApiKey("sk_test_INERT").setMaxNetworkRetries(0).setHttpClient(transport).build();}};
            return new StripeBillingOwnedFailedRenewalObserver(service,f.f.credentials,clients,clock,()->nanos);
        }
        void changeBinding(){var b=binding;binding=new StripeBillingBindingPort.CustomerBinding(b.id(),b.accountId(),b.siteId(),b.customerId(),b.providerReference(),b.creationOperationId(),b.version()+1);}
        void replace(int index,Consumer<com.google.gson.JsonObject> mutation){var replies=new ArrayList<>(f.f.http.replies);var old=(StripeResponse)replies.get(index);
            var json=StripeBillingOwnedSubscriptionFixture.json(old.body());mutation.accept(json);replies.set(index,new StripeResponse(200,HttpHeaders.of(Map.of()),json.toString()));f.f.http.replies.clear();f.f.http.replies.addAll(replies);}
        String readyClock(){var json=StripeBillingOwnedSubscriptionFixture.json(f.f.clockJson());json.addProperty("frozen_time",admission.ready().observation().frozenTime());return json.toString();}
        void graph(){StripeBillingFailedRenewalMappingTest.graph(f);f.f.http.replies.addFirst(new StripeResponse(200,HttpHeaders.of(Map.of()),readyClock()));f.f.http.reply(readyClock());
            var audit=new Runnable(){public void run(){ioInTransaction|=active;ioOnCaller|=Thread.currentThread()==caller;f.f.http.afterResponse=this;}};
            f.f.http.afterResponse=audit;}
    }
}
