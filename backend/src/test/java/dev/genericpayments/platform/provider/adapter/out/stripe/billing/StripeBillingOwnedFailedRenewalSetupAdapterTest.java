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
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual service authority and official SDK parsing; every HTTP response exists only in memory. */
class StripeBillingOwnedFailedRenewalSetupAdapterTest {
    @Test void originalAuthenticatedCreateReturnsOnlyActualUnboundProofAndPinsExactRequest() {
        var f=new Fixture();f.reply();var proof=f.prepare();
        assertThat(proof).isSameAs(f.proof);assertThat(proof.operation()).isEqualTo(f.g.operation().id());
        assertThat(proof.subscription()).isEqualTo(f.g.subscription().id());assertThat(proof.account()).isEqualTo(f.g.account().id());
        assertThat(proof.step()).isEqualTo(f.step.id());assertThat(proof.reference()).isEqualTo("seti_original");assertThat(proof.method()).isNull();
        assertThat(proof.toString()).doesNotContain("secret", "INERT");assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(f.step.version()).isEqualTo(2);
        assertThat(f.sdk.http.calls).hasSize(1);var request=f.sdk.http.calls.getFirst();
        assertThat(request.method().name()).isEqualTo("POST");assertThat(request.url().toString()).isEqualTo("https://api.stripe.com/v1/setup_intents");
        assertThat(request.params()).containsOnlyKeys("customer","usage","allowed_payment_method_types","metadata");
        assertThat(request.params()).containsEntry("customer","cus_owned").containsEntry("usage","off_session").containsEntry("allowed_payment_method_types",List.of("card"));
        assertThat(request.params().get("metadata")).isEqualTo(f.metadata());
        assertThat(request.options().getIdempotencyKey()).isEqualTo(f.g.operation().providerOperationKey()+":SETUP");
        assertThat(request.options().getMaxNetworkRetries()).isZero();assertThat(request.options().getConnectTimeout()).isBetween(1,2000);assertThat(request.options().getReadTimeout()).isBetween(1,2000);
        assertThat(request.options().getStripeAccount()).isNull();assertThat(request.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");
        assertThat(f.events).containsSubsequence("reserve","claim","authorize","dispatch","sdk","ack","record");
        assertThat(f.g.stops()).isZero();assertThat(f.sdk.verifications).isZero();f.g.noBusinessChanges();
    }
    @Test void producerDoesNotRequireOrReturnProviderClientSecret() {
        for(var secret:Arrays.asList(null,"provider-private-invalid-secret")){
            var f=new Fixture();var body=f.body();if(secret!=null)body.addProperty("client_secret",secret);f.sdk.http.reply(body.toString());
            assertThat(f.prepare().reference()).isEqualTo("seti_original");assertThat(f.proof.toString()).doesNotContain("provider-private");f.g.noBusinessChanges();
        }
    }
    @Test void everyExistingProviderStateAndExistingProofRefuseWithoutAdoptionOrStop() {
        for(var status:ProviderOperation.Status.values()){
            var f=new Fixture();f.step=f.fresh();f.step=copy(f.step,status,status==ProviderOperation.Status.ACKNOWLEDGED?"seti_original":null,
                status==ProviderOperation.Status.RESERVED?null:f.g.now(),status==ProviderOperation.Status.RESERVED?null:f.g.now().plusSeconds(82800),
                status==ProviderOperation.Status.DISPATCHING?"other":null,status==ProviderOperation.Status.DISPATCHING?f.g.now().plusSeconds(60):null,status==ProviderOperation.Status.RESERVED?0:2);
            refused(f::prepare);assertThat(f.events).doesNotContain("reserve","claim","sdk","ack","record");assertThat(f.g.stops()).isZero();
        }
        var f=new Fixture();f.proof=new SubscriptionBillingProofStore.Setup(f.g.operation().id(),f.g.subscription().id(),f.g.account().id(),UUID.randomUUID(),"seti_original",null);
        refused(f::prepare);assertThat(f.events).doesNotContain("reserve","claim","sdk");assertThat(f.g.stops()).isZero();
    }
    @Test void completedInvocationCannotRepeatThePostOrAdoptItsOwnAck() {
        var f=new Fixture();f.reply();f.prepare();refused(f::prepare);assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.g.stops()).isZero();
    }
    @Test void changedOriginalKeyRefusesBeforeAnyProviderEffect() {
        var f=new Fixture();var c=f.g.context;var changed=new SubscriptionCommandContext(c.actor(),c.siteId(),c.customerId(),c.origin(),"another-key",c.requestId());
        refused(()->f.adapter().prepare(f.g.reservation,changed));assertThat(f.events).isEmpty();assertThat(f.g.stops()).isZero();
    }
    @Test void claimLoserDoesNotStopAnotherHolder() {
        var f=new Fixture();f.loseClaim=true;refused(f::prepare);
        assertThat(f.g.stops()).isZero();assertThat(f.events).doesNotContain("unknown","sdk","ack","record");
    }
    @Test void competingInvocationMayWinFirstClaimEvenWhenItsInsertLost() {
        var f=new Fixture();f.hideFinds=2;f.reply();var winningProof=new AtomicReference<SubscriptionBillingProofStore.Setup>();
        // Deterministic interleaving: first caller inserted; second saw an empty earlier snapshot,
        // receives that shared row and claims before the inserting caller continues.
        f.on("reserve",()->winningProof.set(f.prepare()));
        refused(f::prepare);
        assertThat(winningProof.get()).isSameAs(f.proof);assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.events.stream().filter("reserve"::equals)).hasSize(2);assertThat(f.g.stops()).isZero();assertThat(f.events).doesNotContain("unknown");
    }
    @Test void malformedReservedRowsNeverAcquireCleanupAuthority() {
        for(var mutation:List.of("fingerprint","kind","resource","key","external","alias","account","version","reference","first")){
            var f=new Fixture();f.reserveMutation=o->mutate(o,mutation);refused(f::prepare);
            assertThat(f.events).doesNotContain("claim","sdk","unknown");assertThat(f.g.stops()).isZero();
        }
    }
    @Test void malformedOrSecondClaimsCannotStopTheActualOwner() {
        for(var mutation:List.of("fingerprint","kind","resource","key","external","alias","account","version","reference","owner","missing-first","missing-retry","missing-lease")){
            var f=new Fixture();f.claimMutation=o->mutate(o,mutation);refused(f::prepare);
            assertThat(f.events).doesNotContain("sdk","unknown");assertThat(f.g.stops()).isZero();
        }
    }
    @Test void driftAfterReserveIsPreclaimRefusalAndNeverStops() {
        var f=new Fixture();f.on("reserve",()->f.g.revoke());refused(f::prepare);
        assertThat(f.events).doesNotContain("sdk","unknown");assertThat(f.g.stops()).isZero();
    }
    @Test void realAuthorityAndIdentityDriftAfterClaimStopExactlyOnce() {
        for(var mutation:List.of("session","authority","sequence","sample","mode","binding","operation")){
            var f=new Fixture();f.on("claim",()->f.g.drift(mutation));refused(f::prepare);
            assertThat(f.events).doesNotContain("sdk","ack","record");assertThat(f.g.stops()).isEqualTo(1);assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        }
    }
    @Test void freshContextMustBeInspectedAfterBlockingDispatchAndSdkBeforeAck() {
        for(var boundary:List.of("dispatch","response")){
            var f=new Fixture();f.reply();f.on(boundary,()->f.g.revoke());refused(f::prepare);
            assertThat(f.events).doesNotContain("ack","record");assertThat(f.g.stops()).isEqualTo(1);
            assertThat(f.sdk.http.calls).hasSize(boundary.equals("dispatch")?0:1);assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        }
    }
    @Test void malformedForeignLiveTerminalOrPreboundProviderResponsesNeverAcknowledge() {
        for(var mutation:List.of("id","object","live","customer","usage","types","succeeded","processing","requires_confirmation","method","early","late","missing-metadata","extra-metadata","account_id","site_id","customer_id","subscription_id","operation_id")){
            var f=new Fixture();var body=f.body();switch(mutation){
                case "id"->body.addProperty("id","wrong-id");case "object"->body.addProperty("object","customer");case "live"->body.addProperty("livemode",true);
                case "customer"->body.addProperty("customer","cus_foreign");case "usage"->body.addProperty("usage","on_session");case "types"->body.add("payment_method_types",JsonParser.parseString("[\"card\",\"link\"]"));
                case "succeeded","processing","requires_confirmation"->body.addProperty("status",mutation);case "method"->body.addProperty("payment_method","pm_prebound");
                case "early"->body.addProperty("created",f.g.now().getEpochSecond()-1);case "late"->body.addProperty("created",f.g.now().getEpochSecond()+82800);
                case "missing-metadata"->body.remove("metadata");case "extra-metadata"->body.getAsJsonObject("metadata").addProperty("foreign","extra");
                default->body.getAsJsonObject("metadata").addProperty(mutation,UUID.randomUUID().toString());
            }
            f.sdk.http.reply(body.toString());refused(f::prepare);assertThat(f.events).doesNotContain("ack","record");assertThat(f.g.stops()).isEqualTo(1);assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        }
    }
    @Test void transportFailureIsTerminalCauseFreeAndCannotBeRetried() {
        var f=new Fixture();f.sdk.http.disconnect();refused(f::prepare);refused(f::prepare);
        assertThat(f.sdk.http.calls).hasSize(1);assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(f.g.stops()).isEqualTo(1);f.g.noBusinessChanges();
    }
    @Test void nullOrMissingProviderEvidenceCannotBecomeAReceipt() {
        for(var field:List.of("null","id","object","livemode","customer","usage","payment_method_types","status","created")){
            var f=new Fixture();var body=f.body();body.remove(field);f.sdk.http.reply(field.equals("null")?"null":body.toString());refused(f::prepare);
            assertThat(f.events).doesNotContain("ack","record");assertThat(f.g.stops()).isEqualTo(1);
        }
    }
    @Test void ackFailureUnknownsOnlyTheStillDispatchingOriginalClaim() {
        var f=new Fixture();f.reply();f.on("before-ack",()->{throw new IllegalStateException("private provider secret");});refused(f::prepare);
        assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(f.g.stops()).isEqualTo(1);assertThat(f.proof).isNull();
    }
    @Test void ackCommittedBeforeThrownFailureAndFailedProofNeverDowngradeAck() {
        for(var boundary:List.of("ack","record")){
            var f=new Fixture();f.reply();f.on(boundary,()->{throw new IllegalStateException("private persistence detail");});refused(f::prepare);
            assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(f.events).doesNotContain("unknown");assertThat(f.g.stops()).isEqualTo(1);assertThat(f.proof).isNull();
            refused(f::prepare);assertThat(f.sdk.http.calls).hasSize(1);
        }
    }
    @Test void failedUnknownCleanupCannotCreateReplacementAuthority() {
        var f=new Fixture();f.sdk.http.disconnect();f.on("unknown",()->{throw new IllegalStateException("private cleanup detail");});refused(f::prepare);refused(f::prepare);
        assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);assertThat(f.g.stops()).isEqualTo(1);assertThat(f.sdk.http.calls).hasSize(1);
    }
    @Test void failedStopStillPreservesUncertaintyAndCannotAuthorizeReplacement() {
        var f=new Fixture();f.g.failStop();f.sdk.http.disconnect();refused(f::prepare);refused(f::prepare);
        assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(f.g.stopped()).isFalse();assertThat(f.g.stops()).isEqualTo(1);assertThat(f.sdk.http.calls).hasSize(1);
    }
    @Test void returnedProofMustMatchEveryOriginalIdentityAndBeUnbound() {
        for(var field:List.of("operation","subscription","account","step","reference","method")){
            var f=new Fixture();f.reply();f.proofMutation=p->new SubscriptionBillingProofStore.Setup(field.equals("operation")?UUID.randomUUID():p.operation(),field.equals("subscription")?UUID.randomUUID():p.subscription(),field.equals("account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(UUID.randomUUID()):p.account(),field.equals("step")?UUID.randomUUID():p.step(),field.equals("reference")?"seti_foreign":p.reference(),field.equals("method")?"pm_attached":null);
            refused(f::prepare);assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(f.events).doesNotContain("unknown");assertThat(f.g.stops()).isEqualTo(1);
        }
    }
    @Test void utcDeadlineIsImmutableAcrossBlockingCallerWorkAndAcceptance() {
        for(var boundary:List.of("reserve","claim","authorize","dispatch","response","ack")){
            var f=new Fixture();f.reply();var entry=f.g.now();f.on(boundary,()->f.g.at(entry.plusSeconds(30)));refused(f::prepare);
            assertThat(f.events).doesNotContain("record");assertThat(f.sdk.http.calls).hasSize(boundary.equals("response")||boundary.equals("ack")?1:0);
            assertThat(f.g.stops()).isEqualTo(boundary.equals("reserve")?0:1);
        }
    }
    @Test void monotonicBudgetCannotBeExtendedByUtcRollback() {
        var f=new Fixture();f.on("claim",()->{f.nanos.set(TimeUnit.SECONDS.toNanos(30));f.g.at(f.g.now().minusSeconds(5));});refused(f::prepare);
        assertThat(f.events).doesNotContain("sdk");assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void leaseExpiryAfterCallerLocksRefusesEvenWhenOtherBudgetsAreUnchanged() {
        var f=new Fixture();f.on("dispatch",()->f.g.at(f.g.now().plusSeconds(60)));refused(f::prepare);assertThat(f.sdk.http.calls).isEmpty();assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void finalSuccessfulUnboundProofMayCommitAfterExpiryOrRevocation() {
        for(var mutation:List.of("expired","revoked")){
            var f=new Fixture();f.reply();f.on("record",()->{if(mutation.equals("expired"))f.g.at(f.g.now().plusSeconds(700));else f.g.revoke();});
            assertThat(f.prepare()).isSameAs(f.proof);assertThat(f.proof.method()).isNull();assertThat(f.g.stops()).isZero();
            assertThatThrownBy(()->f.g.methods.inspectBrowserSetupPreparation(f.g.reservation,f.g.context)).isInstanceOf(RuntimeException.class);f.g.noBusinessChanges();
        }
    }
    @Test void revocationBeforeLastInspectionPreservesAckButRefusesProof() {
        var f=new Fixture();f.reply();f.on("ack",f.g::revoke);refused(f::prepare);
        assertThat(f.step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(f.events).doesNotContain("record","unknown");assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void alreadyInterruptedCallerPreservesFlagAndDoesNotDispatch() {
        var f=new Fixture();Thread.currentThread().interrupt();try{refused(f::prepare);assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(f.sdk.http.calls).isEmpty();}finally{Thread.interrupted();}
    }
    @Test void blockedMemoryTransportIsCancelledWithinCompleteCallBound()throws Exception {
        var f=new Fixture();var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        f.transport=request->{entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){cancelled.countDown();throw new IllegalStateException("private transport secret");}};
        long start=System.nanoTime();refused(f::prepare);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));assertThat(entered.getCount()).isZero();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(f.sdkEntries).hasValue(1);assertThat(f.g.stops()).isEqualTo(1);
    }
    @Test void callerInterruptDuringSdkPreservesFlagAndCancelsOnlyWorker()throws Exception {
        var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var preserved=new AtomicBoolean();var sdkEntries=new AtomicInteger();
        var task=new FutureTask<Void>(()->{var f=new Fixture();f.transport=request->{sdkEntries.incrementAndGet();entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){cancelled.countDown();throw new IllegalStateException("private transport secret");}};
            try{refused(f::prepare);preserved.set(Thread.currentThread().isInterrupted());assertThat(f.g.stops()).isEqualTo(1);return null;}finally{Thread.interrupted();}});
        var caller=Thread.ofVirtual().start(task);try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();caller.interrupt();task.get(2,TimeUnit.SECONDS);assertThat(preserved).isTrue();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(sdkEntries).hasValue(1);}finally{caller.interrupt();task.cancel(true);}
    }

    static void refused(Runnable work){assertThatThrownBy(work::run).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned method setup unavailable").hasNoCause().satisfies(error->{var e=(PaymentApplicationException)error;assertThat(e.code()).isEqualTo("OWNED_METHOD_SETUP_UNAVAILABLE");assertThat(e.status()).isEqualTo(409);assertThat(e.getSuppressed()).isEmpty();});}
    static ProviderOperation copy(ProviderOperation o,ProviderOperation.Status status,String reference,Instant first,Instant retry,String owner,Instant expiry,long version){return StripeBillingInertSdkFixture.copy(o,status,reference,first,retry,owner,expiry,version);}
    static ProviderOperation mutate(ProviderOperation o,String field){return new ProviderOperation(o.id(),field.equals("account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(UUID.randomUUID()):o.accountId(),field.equals("external")?"acct_foreign":o.externalAccountId(),field.equals("alias")?"foreign-alias":o.credentialsRef(),field.equals("kind")?ProviderOperation.Kind.CUSTOMER_CREATE:o.kind(),field.equals("resource")?UUID.randomUUID():o.resourceId(),field.equals("key")?"wrong-key":o.operationKey(),field.equals("fingerprint")?"wrong-fingerprint":o.fingerprint(),o.status(),field.equals("reference")?"seti_existing":o.providerReference(),field.equals("missing-first")?null:field.equals("first")?Instant.EPOCH:o.firstDispatchedAt(),field.equals("missing-retry")?null:o.retryBefore(),field.equals("owner")?"other-holder":o.leaseOwner(),field.equals("missing-lease")?null:o.leaseExpiresAt(),field.equals("version")?o.version()+1:o.version());}
    interface MemoryResponse {StripeResponse request(StripeRequest request)throws StripeException;}
    static final class Fixture {
        final OwnedMethodSetupInertFixture g=new OwnedMethodSetupInertFixture();
        final StripeBillingInertSdkFixture sdk=new StripeBillingInertSdkFixture();
        final Thread caller=Thread.currentThread();final List<String> events=new ArrayList<>();final Map<String,Runnable> hooks=new HashMap<>();
        final AtomicLong nanos=new AtomicLong();final AtomicInteger sdkEntries=new AtomicInteger();
        ProviderOperation step;SubscriptionBillingProofStore.Setup proof;boolean loseClaim;int hideFinds;
        UnaryOperator<ProviderOperation> reserveMutation=UnaryOperator.identity(),claimMutation=UnaryOperator.identity();
        UnaryOperator<SubscriptionBillingProofStore.Setup> proofMutation=UnaryOperator.identity();
        MemoryResponse transport=sdk.http::request;
        Fixture(){g.afterAccountLock(()->{assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.active()).isTrue();});}
        final ProviderOperationStore steps=StripeBillingInertSdkFixture.port(ProviderOperationStore.class,call->{
            caller();var a=call.args();return switch(call.name()){
                case "find"->{assertThat(a[0]).isEqualTo(g.account().id());assertThat(a[1]).isEqualTo(key());yield hideFinds-->0?Optional.empty():Optional.ofNullable(step);}
                case "findById"->{assertThat(a[0]).isEqualTo(step.id());yield Optional.of(step);}
                case "reserveOwnedFailedRenewalMethodStep"->{assertThat(a).containsExactly(g.account(),g.reservation,ProviderOperation.Kind.SETUP_INTENT_CREATE);if(step==null)step=reserveMutation.apply(fresh());var result=step;event("reserve");yield result;}
                case "claim"->{assertThat(a[0]).isEqualTo(step.id());assertThat(a[1]).isInstanceOf(String.class);assertThat((String)a[1]).isNotBlank();assertThat(a[3]).isEqualTo(Duration.ofSeconds(60));event("claim-attempt");if(loseClaim||step.status()!=ProviderOperation.Status.RESERVED)yield Optional.empty();var now=(Instant)a[2];step=claimMutation.apply(copy(step,ProviderOperation.Status.DISPATCHING,null,now,now.plusSeconds(82800),(String)a[1],now.plusSeconds(60),1));event("claim");yield Optional.of(step);}
                case "requireOwnedFailedRenewalMethodDispatch"->{assertThat(a[0]).isEqualTo(g.account());assertThat(a[1]).isEqualTo(g.reservation);assertThat(a[2]).isEqualTo(step);new ProviderOperationGuard().requireDispatch(step,g.account(),fingerprint(),(Instant)a[3]);event("dispatch");yield step;}
                case "acknowledge"->{assertClaim(a);event("before-ack");assertThat(a[3]).isEqualTo("seti_original");step=copy(step,ProviderOperation.Status.ACKNOWLEDGED,(String)a[3],step.firstDispatchedAt(),step.retryBefore(),null,null,2);event("ack");yield step;}
                case "unknown"->{assertClaim(a);event("unknown");step=copy(step,ProviderOperation.Status.UNKNOWN,null,step.firstDispatchedAt(),step.retryBefore(),null,null,2);yield step;}
                default->throw new AssertionError("Unplanned step capability "+call.name());
            };
        });
        final SubscriptionBillingProofStore proofs=StripeBillingInertSdkFixture.port(SubscriptionBillingProofStore.class,call->{
            caller();var a=call.args();return switch(call.name()){
                case "setup"->{assertThat(a).containsExactly(g.account().id(),g.operation().id());yield Optional.ofNullable(proof);}
                case "authorizeSetup"->{assertThat(a).containsExactly(g.subscription(),g.operation());event("authorize");yield null;}
                case "recordSetup"->{assertThat(a).containsExactly(g.subscription(),g.operation(),step.id(),"seti_original");assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);event("record");proof=proofMutation.apply(new SubscriptionBillingProofStore.Setup(g.operation().id(),g.subscription().id(),g.account().id(),step.id(),(String)a[3],null));yield proof;}
                default->throw new AssertionError("Unplanned proof capability "+call.name());
            };
        });
        final StripeClientFactory clients=new StripeClientFactory(){@Override public StripeClient create(StripeCredentials ignored){caller();return StripeClient.builder().setApiKey("sk_test_INERT").setHttpClient(new HttpClient(){@Override public StripeResponse request(StripeRequest request)throws StripeException{
            assertThat(Thread.currentThread()).isNotSameAs(caller);assertThat(g.active()).isFalse();sdkEntries.incrementAndGet();events.add("sdk");var response=transport.request(request);event("response");return response;
        }}).build();}};
        StripeBillingOwnedFailedRenewalSetupAdapter adapter(){return new StripeBillingOwnedFailedRenewalSetupAdapter(g.methods,steps,proofs,sdk.credentials,clients,sdk.digest,g.clock,nanos::get);}
        SubscriptionBillingProofStore.Setup prepare(){return adapter().prepare(g.reservation,g.context);}
        void caller(){assertThat(Thread.currentThread()).isSameAs(caller);assertThat(g.active()).isFalse();}
        void on(String boundary,Runnable effect){hooks.put(boundary,effect);}
        void event(String boundary){events.add(boundary);var effect=hooks.remove(boundary);if(effect!=null)effect.run();}
        String key(){return g.operation().providerOperationKey()+":SETUP";}
        String fingerprint(){var o=g.operation();var s=g.subscription();return StripeRequestFingerprint.of(sdk.digest,g.account(),"SETUP_INTENT_CREATE_V1",o.id().toString(),key(),o.fingerprint(),Long.toString(o.policyVersion()),s.id().toString(),s.siteId().value().toString(),s.customerId().value().toString(),g.binding().providerReference(),"off_session","card","consent:true");}
        ProviderOperation fresh(){return new ProviderOperation(UUID.randomUUID(),g.account().id(),g.account().externalAccountId(),g.account().credentialsRef(),ProviderOperation.Kind.SETUP_INTENT_CREATE,g.operation().id(),key(),fingerprint(),ProviderOperation.Status.RESERVED,null,null,null,null,null,0);}
        Map<String,String> metadata(){return Map.of("account_id",g.account().id().value().toString(),"site_id",g.subscription().siteId().value().toString(),"customer_id",g.subscription().customerId().value().toString(),"subscription_id",g.subscription().id().toString(),"operation_id",g.operation().id().toString());}
        JsonObject body(){var body=new JsonObject();body.addProperty("id","seti_original");body.addProperty("object","setup_intent");body.addProperty("livemode",false);body.addProperty("customer","cus_owned");body.addProperty("usage","off_session");body.addProperty("status","requires_payment_method");body.add("payment_method_types",JsonParser.parseString("[\"card\"]"));body.addProperty("created",g.now().getEpochSecond());body.add("payment_method",JsonNull.INSTANCE);var metadata=new JsonObject();metadata().forEach(metadata::addProperty);body.add("metadata",metadata);return body;}
        void reply(){sdk.http.reply(body().toString());}
        void assertClaim(Object[] a){assertThat(step.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);assertThat(a[0]).isEqualTo(step.id());assertThat(a[1]).isEqualTo(step.leaseOwner());assertThat(a[2]).isEqualTo(step.version());}
    }
}
