package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.*;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedMethodSetupInertFixture;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual original authority and atomic service binding; official SDK transport exists only in memory. */
class StripeBillingOwnedFailedRenewalBindingAdapterTest {
    @Test void originalSucceededSetupAndItsIndependentCardGetBindOnlyTheOriginalProof(){
        var f=new Fixture();f.reply();var bound=f.bind();
        assertThat(bound).isSameAs(f.g.setupProof());assertThat(bound.method()).isEqualTo("pm_confirmed");
        assertThat(bound.operation()).isEqualTo(f.original.operation());assertThat(bound.subscription()).isEqualTo(f.original.subscription());
        assertThat(bound.account()).isEqualTo(f.original.account());assertThat(bound.step()).isEqualTo(f.original.step());assertThat(bound.reference()).isEqualTo("seti_original");
        assertThat(f.sdk.http.calls.stream().map(r->r.method().name()+" "+r.url()).toList())
            .containsExactly("GET https://api.stripe.com/v1/setup_intents/seti_original","GET https://api.stripe.com/v1/payment_methods/pm_confirmed");
        for(var request:f.sdk.http.calls){
            assertThat(request.params()).isNullOrEmpty();assertThat(request.options().getIdempotencyKey()).isNull();
            assertThat(request.options().getMaxNetworkRetries()).isZero();assertThat(request.options().getConnectTimeout()).isBetween(1,2000);
            assertThat(request.options().getReadTimeout()).isBetween(1,2000);assertThat(request.options().getStripeAccount()).isNull();
            assertThat(request.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");
        }
        assertThat(f.events).containsSubsequence("inspect-1","client","sdk-1","response-1","sdk-2","response-2","binding-write","binding-commit");
        assertThat(f.inspections.get()).isGreaterThanOrEqualTo(4);assertThat(f.sdk.http.replies).isEmpty();f.noEffects();
        // Genuine bound proof is usable by later independent METHOD inspection; no METHOD row/lifecycle was fabricated.
        assertThat(f.g.methods.inspectBrowserMethodPreparation(f.g.reservation,f.g.context).setupProof()).isEqualTo(bound);
        refused(f::bind);assertThat(f.sdk.http.calls).hasSize(2);assertThat(f.g.setupProof()).isSameAs(bound);f.noEffects();
    }

    @Test void ordinarySetupValidatorKeepsItsOriginalTwoMetadataKeyContract(){
        var f=new Fixture();assertThat(f.setup().getAsJsonObject("metadata").keySet()).containsExactlyInAnyOrder("operation_id","subscription_id");
        f.reply();assertThat(f.bind().method()).isEqualTo("pm_confirmed");f.noEffects();
    }

    @Test void missingBoundUncommittedOrMalformedOriginalEvidenceRefusesBeforeCredentialsOrGets(){
        for(var bad:List.of("missing-proof","bound","unconsumed","uncommitted","missing-ack","wrong-ack","wrong-reference","fingerprint")){
            var f=new Fixture();var p=f.original;var s=f.originalSetup;
            switch(bad){
                case "missing-proof"->f.g.setupProof(null);
                case "bound"->f.g.setupProof(new Setup(p.operation(),p.subscription(),p.account(),p.step(),p.reference(),"pm_existing"));
                case "unconsumed"->f.g.browserConsumed(false);case "uncommitted"->f.g.browserConsumptionUncommitted(true);
                case "missing-ack"->f.g.setupStep(null);
                case "wrong-ack"->f.g.setupStep(StripeBillingInertSdkFixture.copy(s,ProviderOperation.Status.UNKNOWN,s.providerReference(),s.firstDispatchedAt(),s.retryBefore(),null,null,2));
                case "wrong-reference"->f.g.setupProof(new Setup(p.operation(),p.subscription(),p.account(),p.step(),"seti_foreign",null));
                case "fingerprint"->f.g.setupStep(new ProviderOperation(s.id(),s.accountId(),s.externalAccountId(),s.credentialsRef(),s.kind(),s.resourceId(),s.operationKey(),"wrong",s.status(),s.providerReference(),s.firstDispatchedAt(),s.retryBefore(),s.leaseOwner(),s.leaseExpiresAt(),s.version()));
            }
            var before=f.g.bindingState();refused(f::bind);assertThat(f.g.bindingState()).as(bad).isEqualTo(before);
            assertThat(f.events).doesNotContain("client");assertThat(f.sdkEntries).hasValue(0);f.noForbiddenEffects();
        }
    }

    @Test void originalManagementIdentityKeyOriginAndLiveSessionAreRequiredBeforeAnyGet(){
        for(var bad:List.of("session","authority","key","origin","second-session")){
            var f=new Fixture();var c=f.g.context;
            switch(bad){
                case "session","authority"->f.g.drift(bad);
                case "key"->f.context=new SubscriptionCommandContext(c.actor(),c.siteId(),c.customerId(),c.origin(),"foreign-key",c.requestId());
                case "origin"->f.context=new SubscriptionCommandContext(c.actor(),c.siteId(),c.customerId(),"https://foreign.test",c.idempotencyKey(),c.requestId());
                case "second-session"->{f.context=f.g.secondSession();f.g.revoke();}
            }
            var before=f.g.bindingState();refused(f::bind);assertThat(f.g.bindingState()).as(bad).isEqualTo(before);
            assertThat(f.events).doesNotContain("client");assertThat(f.sdkEntries).hasValue(0);f.noForbiddenEffects();
        }
    }

    @Test void malformedSucceededSetupRefusesBeforeAnyPaymentMethodGetOrBinding(){
        for(var bad:List.of("object","reference","missing-reference","customer","missing-customer","live","missing-live","usage","types","missing-types",
                "status","missing-status","method","missing-method","long-method","path-method","metadata","operation","subscription","created","before-window","window-end")){
            var f=new Fixture();var body=f.setup();
            switch(bad){
                case "object"->body.addProperty("object","payment_intent");case "reference"->body.addProperty("id","seti_foreign");case "missing-reference"->body.remove("id");
                case "customer"->body.addProperty("customer","cus_foreign");case "missing-customer"->body.remove("customer");
                case "live"->body.addProperty("livemode",true);case "missing-live"->body.remove("livemode");case "usage"->body.addProperty("usage","on_session");
                case "types"->body.add("payment_method_types",JsonParser.parseString("[\"card\",\"link\"]"));case "missing-types"->body.remove("payment_method_types");
                case "status"->body.addProperty("status","requires_payment_method");case "missing-status"->body.remove("status");
                case "method"->body.addProperty("payment_method","card_foreign");case "missing-method"->body.add("payment_method",JsonNull.INSTANCE);
                case "long-method"->body.addProperty("payment_method","pm_"+"A".repeat(253));case "path-method"->body.addProperty("payment_method","pm_other/path");
                case "metadata"->body.remove("metadata");case "operation"->body.getAsJsonObject("metadata").addProperty("operation_id",UUID.randomUUID().toString());
                case "subscription"->body.getAsJsonObject("metadata").addProperty("subscription_id",UUID.randomUUID().toString());case "created"->body.remove("created");
                case "before-window"->body.addProperty("created",f.originalSetup.firstDispatchedAt().minusSeconds(1).getEpochSecond());
                case "window-end"->body.addProperty("created",f.originalSetup.retryBefore().getEpochSecond());
            }
            f.sdk.http.reply(body.toString());refused(f::bind);assertThat(f.sdk.http.calls).as(bad).hasSize(1);f.unchanged();
        }
    }

    @Test void methodMustIndependentlyMatchOriginalCustomerSandboxCardAndExactReference(){
        for(var bad:List.of("object","reference","missing-reference","customer","missing-customer","live","missing-live","type","missing-type","card","missing-card")){
            var f=new Fixture();var body=f.card();
            switch(bad){
                case "object"->body.addProperty("object","customer");case "reference"->body.addProperty("id","pm_foreign");case "missing-reference"->body.remove("id");
                case "customer"->body.addProperty("customer","cus_foreign");case "missing-customer"->body.remove("customer");
                case "live"->body.addProperty("livemode",true);case "missing-live"->body.remove("livemode");
                case "type"->body.addProperty("type","sepa_debit");case "missing-type"->body.remove("type");
                case "card"->body.add("card",JsonNull.INSTANCE);case "missing-card"->body.remove("card");
            }
            f.sdk.http.reply(f.setup().toString());f.sdk.http.reply(body.toString());refused(f::bind);
            assertThat(f.sdk.http.calls).as(bad).hasSize(2);f.unchanged();
        }
    }

    @Test void nullOrMalformedSdkBodiesAtEitherReadHaveOnlyTheFixedSafeFailure(){
        for(int read:new int[]{1,2})for(var body:List.of("null","{}","not-json")){
            var f=new Fixture();if(read==2)f.sdk.http.reply(f.setup().toString());f.sdk.http.reply(body);refused(f::bind);
            assertThat(f.sdk.http.calls).hasSize(read);f.unchanged();
        }
    }

    @Test void credentialAndClientFailuresRefuseSafelyWithoutAnyGet(){
        for(var bad:List.of("credential","client")){
            var f=new Fixture();if(bad.equals("credential"))f.credentials=new StripeCredentialResolver(new StripeSandboxProperties(false,"sk_test_INERT","pk_test_INERT","whsec_INERT"));
            else f.on("client",()->{throw new IllegalStateException("private client secret detail");});
            refused(f::bind);assertThat(f.sdkEntries).hasValue(0);f.unchanged();
        }
    }

    @Test void everyReadUsesFullOriginalReinspectionAfterCredentialAndClientWork(){
        for(var bad:List.of("session","authority","binding","sample","mode","operation","sequence","consumption","proof","setup")){
            var f=new Fixture();var after=new AtomicReference<List<Object>>();
            f.on("client",()->{f.drift(bad);after.set(f.g.bindingState());});refused(f::bind);
            assertThat(f.sdkEntries).as(bad).hasValue(0);assertThat(f.g.bindingState()).isEqualTo(after.get());f.noForbiddenEffects();
        }
    }

    @Test void changedAuthorityOrOriginalContextBetweenReadsCannotReachSecondGetOrBind(){
        for(var bad:List.of("session","authority","binding","sample","mode","operation","sequence","consumption","proof","setup")){
            var f=new Fixture();f.reply();var after=new AtomicReference<List<Object>>();
            f.on("response-1",()->{f.drift(bad);after.set(f.g.bindingState());});refused(f::bind);
            assertThat(f.sdk.http.calls).as(bad).hasSize(1);assertThat(f.g.bindingState()).isEqualTo(after.get());f.noForbiddenEffects();
        }
    }

    @Test void changedAuthorityOrOriginalContextAfterBothReadsCannotReachFinalBinding(){
        for(var bad:List.of("session","authority","binding","sample","mode","operation","sequence","consumption","proof","setup")){
            var f=new Fixture();f.reply();var after=new AtomicReference<List<Object>>();
            f.on("response-2",()->{f.drift(bad);after.set(f.g.bindingState());});refused(f::bind);
            assertThat(f.sdk.http.calls).as(bad).hasSize(2);assertThat(f.g.bindingState()).isEqualTo(after.get());f.noForbiddenEffects();
        }
    }

    @Test void concurrentCommittedBindingIsNeverAdoptedOrReplaced(){
        var f=new Fixture();f.reply();f.on("response-2",()->{var p=f.original;f.g.setupProof(new Setup(p.operation(),p.subscription(),p.account(),p.step(),p.reference(),"pm_competing"));});
        refused(f::bind);var committed=f.g.setupProof();assertThat(committed.method()).isEqualTo("pm_competing");assertThat(f.sdk.http.calls).hasSize(2);
        refused(f::bind);assertThat(f.g.setupProof()).isSameAs(committed);assertThat(f.sdk.http.calls).hasSize(2);f.noForbiddenEffects();
    }

    @Test void authorityOrExpiryLostInsideFinalBindingRollsBackTheActualProofMutation(){
        for(var boundary:List.of("before-write","after-write"))for(var bad:List.of("session","authority","expiry")){
            var f=new Fixture();f.reply();Runnable mutation=()->{if(bad.equals("expiry"))f.g.at(f.expiry());else f.g.drift(bad);};
            if(boundary.equals("before-write"))f.g.beforeBindingWrite(mutation);else f.on("binding-write",mutation);
            refused(f::bind);assertThat(f.g.setupProof()).isEqualTo(f.original);assertThat(f.sdk.http.calls).hasSize(2);
            assertThat(f.g.trace()).contains("binding-write","rollback");f.noEffects();
        }
    }

    @Test void actualCommittedBindingReturnsTruthfullyAfterFinalBudgetOrSessionBoundary(){
        for(var bad:List.of("utc-budget","injected-budget","expiry","revocation")){
            var f=new Fixture();f.reply();f.on("binding-commit",()->{switch(bad){
                case "utc-budget"->f.g.at(f.g.now().plusSeconds(30));case "injected-budget"->f.nanos.set(TimeUnit.SECONDS.toNanos(30));
                case "expiry"->f.g.at(f.expiry());case "revocation"->f.g.revoke();
            }});
            var bound=f.bind();assertThat(bound).isSameAs(f.g.setupProof());assertThat(bound.method()).isEqualTo("pm_confirmed");
            assertThat(f.sdk.http.calls).hasSize(2);f.noEffects();
            if(bad.equals("expiry")||bad.equals("revocation"))assertThatThrownBy(()->f.g.methods.inspectBrowserMethodPreparation(f.g.reservation,f.g.context)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void fixedInvocationBudgetIncludesInitialInspectionClientConstructionAndBothReads(){
        for(var boundary:List.of("inspect-1","client","response-1","response-2"))for(var clock:List.of("utc","nanos")){
            var f=new Fixture();f.reply();var entered=f.g.now();f.on(boundary,()->{if(clock.equals("utc"))f.g.at(entered.plusSeconds(30));else f.nanos.set(TimeUnit.SECONDS.toNanos(30));});
            refused(f::bind);assertThat(f.sdk.http.calls).as(boundary+" "+clock).hasSize(boundary.equals("response-2")?2:boundary.equals("response-1")?1:0);f.unchanged();
            if(clock.equals("nanos"))assertThat(f.g.now()).isEqualTo(entered);
        }
    }

    @Test void utcRollbackAndInjectedMonotonicRollbackCannotExtendReadAuthority(){
        for(var boundary:List.of("client","response-1","response-2"))for(var clock:List.of("utc","nanos")){
            var f=new Fixture();f.reply();f.on(boundary,()->{if(clock.equals("utc"))f.g.at(f.g.now().minusNanos(1));else f.nanos.set(-1);});
            refused(f::bind);assertThat(f.sdk.http.calls).hasSize(boundary.equals("response-2")?2:boundary.equals("response-1")?1:0);f.unchanged();
        }
    }

    @Test void eachCompleteGetHasItsOwnImmutableTwoSecondBound(){
        for(int read:new int[]{1,2}){
            var f=new Fixture();f.reply();f.on("response-"+read,()->f.g.at(f.g.now().plusSeconds(2)));
            refused(f::bind);assertThat(f.sdk.http.calls).hasSize(read);f.unchanged();
        }
    }

    @Test void schedulingEitherWorkerCannotExtendCallOrInvocationUtcLimits(){
        for(int read:new int[]{1,2})for(long seconds:new long[]{2,30}){
            var f=new Fixture();f.reply();var entered=f.g.now();f.invocationClock=f.workerClock(read,entered,entered.plusSeconds(seconds));
            refused(f::bind);assertThat(f.sdkEntries).hasValue(read-1);assertThat(f.sdk.http.calls).hasSize(read-1);f.unchanged();
        }
    }

    @Test void schedulingEitherWorkerCannotExtendInjectedInvocationLimitWithFrozenUtc(){
        for(int read:new int[]{1,2}){
            var f=new Fixture();f.reply();f.invocationNanos=()->Thread.currentThread()!=f.caller&&f.sdkEntries.get()>=read-1?TimeUnit.SECONDS.toNanos(30):0;
            refused(f::bind);assertThat(f.sdkEntries).hasValue(read-1);assertThat(f.sdk.http.calls).hasSize(read-1);f.unchanged();
        }
    }

    @Test void eitherWorkerAtExactOriginalSessionExpiryCannotBeginSdkWithinOtherBudgets(){
        for(int read:new int[]{1,2}){
            var f=new Fixture();var entered=f.expiry().minusMillis(500);f.g.at(entered);f.reply();f.invocationClock=f.workerClock(read,entered,f.expiry());
            refused(f::bind);assertThat(f.sdkEntries).hasValue(read-1);assertThat(f.sdk.http.calls).hasSize(read-1);f.unchanged();
        }
    }

    @Test void readErrorsHaveNoApplicationOrSdkRetryAndDoNotConsumeTheUnboundProof(){
        for(int read:new int[]{1,2})for(var failure:List.of("api","disconnect","runtime")){
            var f=new Fixture();if(read==2)f.sdk.http.reply(f.setup().toString());
            if(failure.equals("api"))f.sdk.http.error(503);else if(failure.equals("disconnect"))f.sdk.http.disconnect();
            else f.on("sdk-"+read,()->{throw new IllegalStateException("secret provider detail");});
            refused(f::bind);assertThat(f.sdkEntries).hasValue(read);assertThat(f.sdk.http.calls).hasSize(failure.equals("runtime")?read-1:read);f.unchanged();
            // A new invocation can inspect the still-unbound original; it grants no new browser write permission.
            f.reply();assertThat(f.bind().method()).isEqualTo("pm_confirmed");assertThat(f.sdkEntries).hasValue(read+2);f.noEffects();
        }
    }

    @Test void alreadyInterruptedCallerPreservesFlagAndNeverResolvesClientOrReads(){
        var f=new Fixture();Thread.currentThread().interrupt();
        try{refused(f::bind);assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(f.events).doesNotContain("client");assertThat(f.sdkEntries).hasValue(0);f.unchanged();}
        finally{Thread.interrupted();}
    }

    @Test void frozenInjectedAndUtcClocksCannotExtendRealCompleteGetWaiting()throws Exception{
        for(int read:new int[]{1,2}){
            var f=new Fixture();if(read==2)f.sdk.http.reply(f.setup().toString());var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
            var ordinary=f.transport;f.transport=request->{if(f.sdkEntries.get()!=read)return ordinary.request(request);entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException interrupted){cancelled.countDown();throw new IllegalStateException("private timeout detail");}};
            long started=System.nanoTime();refused(f::bind);assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(4));
            assertThat(entered.getCount()).isZero();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(f.sdkEntries).hasValue(read);f.unchanged();
        }
    }

    @Test void realInvocationBudgetIncludesCallerDelayWithInjectedAndUtcClocksFrozen()throws Exception{
        var f=new Fixture();var entered=f.g.now();f.on("client",()->{try{new CountDownLatch(1).await(30,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException();}});
        // One deliberately real 30s caller delay proves System.nanoTime is enforced independently of both test clocks.
        refused(f::bind);assertThat(f.g.now()).isEqualTo(entered);assertThat(f.nanos).hasValue(0);assertThat(f.sdkEntries).hasValue(0);f.unchanged();
    }

    @Test void interruptionDuringEitherSdkReadPreservesCallerFlagAndCancelsOnlyItsWorker()throws Exception{
        for(int read:new int[]{1,2}){
            var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var preserved=new AtomicBoolean();var entries=new AtomicInteger();
            var task=new FutureTask<Void>(()->{var f=new Fixture();if(read==2)f.sdk.http.reply(f.setup().toString());var ordinary=f.transport;
                f.transport=request->{entries.incrementAndGet();if(f.sdkEntries.get()!=read)return ordinary.request(request);entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException interrupted){cancelled.countDown();throw new IllegalStateException("private interruption detail");}};
                try{refused(f::bind);preserved.set(Thread.currentThread().isInterrupted());f.unchanged();return null;}finally{Thread.interrupted();}});
            var caller=Thread.ofVirtual().start(task);
            try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();caller.interrupt();task.get(2,TimeUnit.SECONDS);assertThat(preserved).isTrue();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(entries).hasValue(read);}
            finally{caller.interrupt();task.cancel(true);}
        }
    }

    static void refused(Runnable work){assertThatThrownBy(work::run).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned method binding unavailable").hasNoCause().satisfies(failure->{
        var safe=(PaymentApplicationException)failure;assertThat(safe.code()).isEqualTo("OWNED_METHOD_BINDING_UNAVAILABLE");assertThat(safe.status()).isEqualTo(409);assertThat(safe.getSuppressed()).isEmpty();
    });}
    @FunctionalInterface interface MemoryResponse {StripeResponse request(StripeRequest request)throws StripeException;}
    static final class Fixture {
        final OwnedMethodSetupInertFixture g=new OwnedMethodSetupInertFixture();final StripeBillingInertSdkFixture sdk=new StripeBillingInertSdkFixture();
        final Thread caller=Thread.currentThread();final List<String> events=Collections.synchronizedList(new ArrayList<>());
        final Map<String,Runnable> hooks=new ConcurrentHashMap<>();final AtomicLong nanos=new AtomicLong();final AtomicInteger sdkEntries=new AtomicInteger(),inspections=new AtomicInteger();
        final Setup original;final ProviderOperation originalSetup;final Map<String,ProviderOperation> originalSteps;
        Clock invocationClock=g.clock;LongSupplier invocationNanos=nanos::get;SubscriptionCommandContext context=g.context;StripeCredentialResolver credentials=sdk.credentials;
        MemoryResponse transport=sdk.http::request;
        Fixture(){
            g.unboundOriginalSetup(sdk.digest);original=g.setupProof();originalSetup=g.setupStep();originalSteps=g.providerSteps();
            g.afterAccountLock(()->{assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.active()).isTrue();});
            g.afterUnboundSetup(()->{assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.active()).isTrue();event("inspect-"+inspections.incrementAndGet());});
            g.afterBindingWrite(()->{assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.active()).isTrue();event("binding-write");});
            g.afterBindingCommit(()->{assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.active()).isFalse();event("binding-commit");});
        }
        final StripeClientFactory clients=new StripeClientFactory(){@Override public StripeClient create(StripeCredentials credential){
            assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.active()).isFalse();assertThat(credential).isEqualTo(sdk.credentials.resolve(g.account()));event("client");
            return StripeClient.builder().setApiKey("sk_test_INERT").setApiBase("https://api.stripe.com").setHttpClient(new HttpClient(){@Override public StripeResponse request(StripeRequest request)throws StripeException{
                assertThat(Thread.currentThread()).isNotSameAs(caller);assertThat(g.active()).isFalse();int read=sdkEntries.incrementAndGet();event("sdk-"+read);
                var response=transport.request(request);event("response-"+read);return response;
            }}).build();
        }};
        StripeBillingOwnedFailedRenewalBindingAdapter adapter(){return new StripeBillingOwnedFailedRenewalBindingAdapter(g.methods,credentials,clients,invocationClock,invocationNanos);}
        Setup bind(){return adapter().bind(g.reservation,context);}
        void on(String event,Runnable action){hooks.put(event,action);}
        void event(String event){events.add(event);var action=hooks.remove(event);if(action!=null)action.run();}
        Instant expiry(){return ((SubscriptionActor.Customer)g.context.actor()).session().expiresAt();}
        Clock workerClock(int read,Instant callerTime,Instant workerTime){return new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Thread.currentThread()!=caller&&sdkEntries.get()>=read-1?workerTime:callerTime;}};}
        void drift(String bad){switch(bad){
            case "consumption"->g.browserConsumed(false);case "proof"->{var p=g.setupProof();g.setupProof(new Setup(p.operation(),p.subscription(),p.account(),p.step(),"seti_changed",null));}
            case "setup"->{var s=g.setupStep();g.setupStep(StripeBillingInertSdkFixture.copy(s,ProviderOperation.Status.UNKNOWN,s.providerReference(),s.firstDispatchedAt(),s.retryBefore(),null,null,2));}
            default->g.drift(bad);
        }}
        JsonObject setup(){var b=new JsonObject();b.addProperty("id","seti_original");b.addProperty("object","setup_intent");b.addProperty("livemode",false);b.addProperty("customer",g.binding().providerReference());
            b.addProperty("usage","off_session");b.addProperty("status","succeeded");b.add("payment_method_types",JsonParser.parseString("[\"card\"]"));b.addProperty("payment_method","pm_confirmed");b.addProperty("created",originalSetup.firstDispatchedAt().getEpochSecond());
            var metadata=new JsonObject();metadata.addProperty("operation_id",g.operation().id().toString());metadata.addProperty("subscription_id",g.subscription().id().toString());b.add("metadata",metadata);return b;}
        JsonObject card(){var b=new JsonObject();b.addProperty("id","pm_confirmed");b.addProperty("object","payment_method");b.addProperty("livemode",false);b.addProperty("customer",g.binding().providerReference());b.addProperty("type","card");b.add("card",JsonParser.parseString("{\"brand\":\"visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2030}"));return b;}
        void reply(){sdk.http.reply(setup().toString());sdk.http.reply(card().toString());}
        void noForbiddenEffects(){assertThat(g.stops()).isZero();assertThat(g.providerSteps().keySet()).containsExactlyElementsOf(originalSteps.keySet());assertThat(g.trace()).doesNotContain("prepared","claim","facts","completion","publication");assertThat(sdk.verifications).isZero();assertThat(sdk.binds).isZero();}
        void noEffects(){g.noBusinessChanges();noForbiddenEffects();assertThat(g.providerSteps()).isEqualTo(originalSteps);assertThat(g.setupStep()).isEqualTo(originalSetup);}
        void unchanged(){noEffects();assertThat(g.setupProof()).isEqualTo(original);assertThat(events).doesNotContain("binding-write","binding-commit");}
    }
}
