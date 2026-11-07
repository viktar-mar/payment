package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.net.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.Pending;
import dev.genericpayments.platform.provider.application.port.out.OwnedMethodBrowserReader;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StripeOwnedMethodBrowserReaderTest {
    final ReaderContext f=new ReaderContext();
    final StripeBillingInertSdkFixture sdk=f.g.f.f;
    StripeOwnedMethodBrowserReader reader(){return new StripeOwnedMethodBrowserReader(sdk.credentials,sdk.clients,f.clock);}
    String body(){var p=f.pending();return "{\"id\":\"seti_owned\",\"object\":\"setup_intent\",\"livemode\":false,\"customer\":\""+p.binding().providerReference()+"\",\"usage\":\"off_session\",\"payment_method_types\":[\"card\"],\"status\":\"requires_payment_method\",\"payment_method\":null,\"created\":"+p.setupStep().firstDispatchedAt().getEpochSecond()+",\"metadata\":{\"operation_id\":\""+p.operation().id()+"\",\"subscription_id\":\""+p.subscription().id()+"\"},\"client_secret\":\"seti_owned_secret_INERT\"}";}
    @Test void oneExactOriginalGetPinsApiVersionTimeoutsAndNoRetries(){
        sdk.http.reply(body());var result=reader().read(f.pending(),f.now().plusSeconds(30));
        assertThat(result.setupReference()).isEqualTo("seti_owned");assertThat(result.publishableKey()).isEqualTo("pk_test_INERT");assertThat(result.clientSecret()).isEqualTo("seti_owned_secret_INERT");assertThat(sdk.http.calls).hasSize(1);
        var request=sdk.http.calls.getFirst();assertThat(request.method().name()).isEqualTo("GET");assertThat(request.url().toString()).isEqualTo("https://api.stripe.com/v1/setup_intents/seti_owned");
        assertThat(request.options().getMaxNetworkRetries()).isZero();assertThat(request.options().getConnectTimeout()).isBetween(1,2000);assertThat(request.options().getReadTimeout()).isBetween(1,2000);
        assertThat(request.headers().map().get("Stripe-Version")).containsExactly(StripeBillingTestClockAdapter.API_VERSION);assertThat(request.options().getIdempotencyKey()).isNull();assertThat(request.options().getStripeAccount()).isNull();assertThat(sdk.verifications).isZero();assertThat(result.toString()).doesNotContain("INERT","seti_owned");f.noBusinessChanges();
    }
    @Test void originalIdentityPendingShapeAndSecretsAreStrict(){
        var original=body();var created=f.pending().setupStep().firstDispatchedAt().getEpochSecond();
        var corrupt=List.of(
            original.replace("seti_owned\"","seti_foreign\""),original.replace("\"livemode\":false","\"livemode\":true"),
            original.replace(f.pending().binding().providerReference(),"cus_foreign"),original.replace("off_session","on_session"),
            original.replace("[\"card\"]","[\"card\",\"link\"]"),original.replace("requires_payment_method","succeeded"),original.replace("requires_payment_method","requires_confirmation"),
            original.replace("\"payment_method\":null","\"payment_method\":\"pm_attached\""),original.replace(f.pending().operation().id().toString(),UUID.randomUUID().toString()),
            original.replace(f.pending().subscription().id().toString(),UUID.randomUUID().toString()),original.replace("seti_owned_secret_INERT","seti_foreign_secret_INERT"),
            original.replace("seti_owned_secret_INERT","seti_owned_secret_"),original.replace("seti_owned_secret_INERT","seti_owned_secret_bad-suffix"),
            original.replace("seti_owned_secret_INERT","seti_owned_secret_"+"A".repeat(500)),original.replace("\"created\":"+created,"\"created\":"+(created-1)));
        for(var body:corrupt){sdk.http.reply(body);refused(()->reader().read(f.pending(),f.now().plusSeconds(30)));}
        assertThat(sdk.http.calls).hasSize(corrupt.size());
    }
    @Test void accountAndPersistedReferenceDriftRefuseBeforeGet(){
        var p=f.pending();var a=p.account();var live=new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount(a.id(),a.key(),a.provider(),dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode.LIVE,a.status(),a.externalAccountId(),a.credentialsRef());
        var foreign=new Pending(p.reservation(),p.enrollment(),live,p.subscription(),p.operation(),p.binding(),p.setup(),p.setupStep());refused(()->reader().read(foreign,f.now().plusSeconds(30)));
        var proof=p.setup();var changed=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup(proof.operation(),proof.subscription(),proof.account(),proof.step(),"seti_foreign",null);
        refused(()->reader().read(new Pending(p.reservation(),p.enrollment(),p.account(),p.subscription(),p.operation(),p.binding(),changed,p.setupStep()),f.now().plusSeconds(30)));assertThat(sdk.http.calls).isEmpty();
    }
    @Test void providerErrorsAreFixedCauseFreeAndDoNotRetry(){sdk.http.disconnect();refused(()->reader().read(f.pending(),f.now().plusSeconds(30)));assertThat(sdk.http.calls).hasSize(1);}
    @Test void utcDeadlineCapsRequestAndLateCompletionCannotReturnSecret(){sdk.http.reply(body());var deadline=f.now().plusMillis(70);sdk.http.afterResponse=()->f.at(deadline);refused(()->reader().read(f.pending(),deadline));assertThat(sdk.http.calls).hasSize(1);assertThat(sdk.http.calls.getFirst().options().getReadTimeout()).isBetween(1,70);refused(()->reader().read(f.pending(),deadline));assertThat(sdk.http.calls).hasSize(1);}
    @Test void interruptedCallerIsPreservedAndNoGetStarts(){Thread.currentThread().interrupt();try{refused(()->reader().read(f.pending(),f.now().plusSeconds(30)));assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(sdk.http.calls).isEmpty();}finally{Thread.interrupted();}}
    @Test void interruptionDuringGetCancelsWorkerAndPreservesCallerFlag()throws Exception{
        var entered=new CountDownLatch(1);var stopped=new CountDownLatch(1);var preserved=new AtomicBoolean();var count=new AtomicInteger();
        var transport=new HttpClient(){public StripeResponse request(StripeRequest request){count.incrementAndGet();entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){stopped.countDown();throw new IllegalStateException("private provider detail");}}};
        var factory=new StripeClientFactory(){public StripeClient create(StripeCredentials ignored){return StripeClient.builder().setApiKey("sk_test_INERT").setHttpClient(transport).build();}};
        var reader=new StripeOwnedMethodBrowserReader(sdk.credentials,factory,f.clock);var task=new FutureTask<Void>(()->{try{refused(()->reader.read(f.pending(),f.now().plusSeconds(30)));preserved.set(Thread.currentThread().isInterrupted());return null;}finally{Thread.interrupted();}});var caller=Thread.ofVirtual().start(task);
        try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();caller.interrupt();task.get(1,TimeUnit.SECONDS);assertThat(preserved).isTrue();assertThat(stopped.await(1,TimeUnit.SECONDS)).isTrue();assertThat(count).hasValue(1);}finally{caller.interrupt();task.cancel(true);}
    }
    @Test void blockedGetIsCancelledAtBoundWithoutSocketOrSecondRead()throws Exception{var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var count=new AtomicInteger();var transport=new HttpClient(){public StripeResponse request(StripeRequest request){count.incrementAndGet();entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError("unreachable");}catch(InterruptedException e){cancelled.countDown();throw new IllegalStateException("private provider detail");}}};var factory=new StripeClientFactory(){public StripeClient create(StripeCredentials ignored){return StripeClient.builder().setApiKey("sk_test_INERT").setHttpClient(transport).build();}};var reader=new StripeOwnedMethodBrowserReader(sdk.credentials,factory,f.clock);long start=System.nanoTime();refused(()->reader.read(f.pending(),f.now().plusSeconds(30)));assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(cancelled.await(1,TimeUnit.SECONDS)).isTrue();assertThat(count).hasValue(1);}
    @Test void tokenAdapterUsesExistingVersionHashAndMacWithoutExposingConfiguration(){var utility=new StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])));var adapter=new StripeOwnedMethodBrowserTokens(utility);var token=adapter.token("v1","inert descriptor");assertThat(adapter.activeVersion()).isEqualTo("v1");assertThat(token).isEqualTo(utility.token("v1","inert descriptor"));assertThat(adapter.hash(token)).isEqualTo(StripeBillingGrantTokens.hash(token));assertThat(adapter.matches(token,adapter.hash(token))).isTrue();assertThat(adapter.matches(token,"0".repeat(64))).isFalse();assertThat(adapter.toString()).doesNotContain(token);assertThat(new OwnedMethodBrowserReader.EphemeralSetup("seti_owned","pk_test_INERT","seti_owned_secret_INERT").toString()).doesNotContain("INERT");}
    static void refused(Runnable work){assertThatThrownBy(work::run).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned method browser unavailable").hasNoCause();}
    /** Existing pure owned graph has a real supported default credential reference. */
    static final class ReaderContext {
        final StripeBillingOwnedMethodMappingTest.Graph g=new StripeBillingOwnedMethodMappingTest.Graph();
        Instant utc=g.at;final Object original=g.f.local;final Pending pending;
        final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return utc;}};
        ReaderContext(){
            var old=g.candidate.operation();
            var o=new dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation(old.id(),old.siteId(),old.customerId(),old.subscriptionId(),old.kind(),dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.RESERVED,old.authorityScope(),old.keyDigest(),old.fingerprint(),old.providerOperationKey(),old.expectedRevision(),old.policyVersion(),null,null,old.createdAt(),old.createdAt(),0);
            var oldStep=g.candidate.setupStep();var step=new dev.genericpayments.platform.provider.application.model.ProviderOperation(oldStep.id(),oldStep.accountId(),oldStep.externalAccountId(),oldStep.credentialsRef(),oldStep.kind(),oldStep.resourceId(),oldStep.operationKey(),oldStep.fingerprint(),oldStep.status(),"seti_owned",oldStep.firstDispatchedAt(),oldStep.retryBefore(),null,null,2);
            var sample=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort.Sample(Instant.ofEpochSecond(g.admission.ready().ack().advance().target()),dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort.Mode.ISOLATED);
            var r=new dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation(UUID.randomUUID(),g.admission,g.failure,o.id(),o.fingerprint(),0,g.f.binding(),sample,g.failureAt.plusSeconds(1));
            var proof=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup(o.id(),o.subscriptionId(),step.accountId(),step.id(),"seti_owned",null);
            pending=new Pending(r,new dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.Enrollment(UUID.randomUUID(),"https://shop.test",utc.plusSeconds(600)),g.f.f.account,g.f.local,o,g.f.binding(),proof,step);
        }
        Pending pending(){return pending;}Instant now(){return utc;}void at(Instant value){utc=value;}
        void noBusinessChanges(){assertThat(g.f.local).isSameAs(original);assertThat(g.f.f.rows).isEmpty();}
    }
}
