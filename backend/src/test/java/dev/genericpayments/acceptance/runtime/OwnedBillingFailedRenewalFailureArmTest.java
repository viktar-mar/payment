package dev.genericpayments.acceptance.runtime;

import com.stripe.StripeClient;
import com.stripe.net.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingTestClockAdapter;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Inert fixture evidence only: fabricated predecessors never authenticate a native scenario. */
class OwnedBillingFailedRenewalFailureArmTest {
    @Test void fixedAttachThenDefaultUpdateHaveDistinctStableKeysAndPinnedRequestBounds() {
        var f=new Fixture();var result=f.arm().arm();
        var posts=f.posts();assertThat(posts).hasSize(2);
        assertThat(posts.get(0).url().getPath()).isEqualTo("/v1/payment_methods/pm_card_chargeCustomerFail/attach");
        assertThat(posts.get(0).params()).containsOnlyKeys("customer").containsEntry("customer","cus_owned");
        assertThat(posts.get(1).url().getPath()).isEqualTo("/v1/subscriptions/sub_owned");
        assertThat(posts.get(1).params()).containsOnlyKeys("default_payment_method","proration_behavior")
            .containsEntry("default_payment_method","pm_attached").containsEntry("proration_behavior","none");
        assertThat(posts.get(0).options().getIdempotencyKey()).isEqualTo("failed-renewal-arm-v1:"+f.owner.run()+":"+f.admission.id()+":attach");
        assertThat(posts.get(1).options().getIdempotencyKey()).isEqualTo("failed-renewal-arm-v1:"+f.owner.run()+":"+f.admission.id()+":default");
        assertThat(f.http.calls).hasSizeLessThanOrEqualTo(24).allSatisfy(r->{
            assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.options().getConnectTimeout()).isBetween(1,2000);
            assertThat(r.options().getReadTimeout()).isBetween(1,2000);assertThat(r.options().getStripeAccount()).isNull();
            assertThat(r.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");
        });
        assertThat(f.store.readyReads).isGreaterThanOrEqualTo(4);assertThat(f.store.boundaryReads).isEqualTo(f.store.readyReads);
        assertThat(f.contextReads).isGreaterThanOrEqualTo(4);
        assertThat(result.toString()).isEqualTo("FailureArmEvidence[redacted]");
    }

    @Test void duplicateArmDoesNotRedispatchOrStopTheOriginalBoundary() {
        var f=new Fixture();var arm=f.arm();arm.arm();int calls=f.http.calls.size();
        assertThatThrownBy(arm::arm).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(f.http.calls).hasSize(calls);assertThat(f.posts()).hasSize(2);assertThat(f.store.stops).isZero();
    }

    @Test void concurrentArmRefusalCannotStealFirstOwnersAcknowledgment() throws Exception {
        var f=new Fixture();var arm=f.arm();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var result=new AtomicReference<Object>();var failure=new AtomicReference<Throwable>();
        f.http.afterAttach=()->{entered.countDown();try {if(!release.await(1,TimeUnit.SECONDS))throw new AssertionError("Inert release timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}};
        var worker=new Thread(()->{try{result.set(arm.arm());}catch(Throwable problem){failure.set(problem);}},"inert-arm-owner");
        worker.start();try {
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(arm::arm).hasMessage("FAILED_RENEWAL_STOPPED");assertThat(f.store.stops).isZero();
        }finally{release.countDown();worker.join(3000);}
        assertThat(worker.isAlive()).isFalse();assertThat(failure.get()).isNull();assertThat(result.get()).isNotNull();assertThat(f.posts()).hasSize(2);
    }

    @Test void higherPreparedOrUnknownFinalizationRefusesBeforeAnySdkCall() {
        for(String state:List.of("PREPARED","UNKNOWN","DISPATCHING")) {
            var f=new Fixture();f.store.higher=state;
            assertThatThrownBy(()->f.arm().arm()).hasMessage("FAILED_RENEWAL_STOPPED");
            assertThat(f.http.calls).isEmpty();assertThat(f.store.stops).isZero();
        }
    }

    @Test void driftAfterAttachRefusesUpdateAndConsumesOpportunity() {
        var f=new Fixture();var arm=f.arm();f.http.afterAttach=()->f.store.higher="PREPARED";
        assertThatThrownBy(arm::arm).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(f.posts()).hasSize(1);int calls=f.http.calls.size();
        assertThatThrownBy(arm::arm).hasMessage("FAILED_RENEWAL_STOPPED");assertThat(f.http.calls).hasSize(calls);
    }

    @Test void wrongRemoteCustomerClockMethodOrLiveModeNeverGetsASecondWrite() {
        for(String change:List.of("customer","clock","method","live")) {
            var f=new Fixture();f.http.mutate=body->switch(change) {
                case "customer"->body.replace("\"customer\":\"cus_owned\"","\"customer\":\"cus_foreign\"");
                case "clock"->body.replace("\"test_clock\":\"clock_owned\"","\"test_clock\":\"clock_foreign\"");
                case "method"->body.replace("\"default_payment_method\":\"pm_attached\"","\"default_payment_method\":\"pm_foreign\"");
                default->body.replace("\"livemode\":false","\"livemode\":true");
            };
            assertThatThrownBy(()->f.arm().arm()).hasMessage("FAILED_RENEWAL_STOPPED");
            assertThat(f.posts()).hasSizeLessThanOrEqualTo(change.equals("method")?2:1);
        }
    }

    @Test void expiredInvocationAndForeignOriginalSessionHaveNoTransportEffects() {
        var f=new Fixture();f.utc.elapsed.set(Duration.ofSeconds(120).toNanos());
        assertThatThrownBy(()->f.arm().arm()).hasMessage("FAILED_RENEWAL_STOPPED");assertThat(f.http.calls).isEmpty();
        var foreign=new Fixture();foreign.foreignContext=true;
        assertThatThrownBy(()->foreign.arm().arm()).hasMessage("FAILED_RENEWAL_STOPPED");assertThat(foreign.http.calls).isEmpty();
    }

    @Test void elapsedDeadlineAfterAttachPreventsUpdateEvenWithFixedCallerClock() {
        var f=new Fixture();f.http.afterAttach=()->f.nanos.addAndGet(Duration.ofSeconds(31).toNanos());var arm=f.arm();
        assertThatThrownBy(arm::arm).hasMessage("FAILED_RENEWAL_STOPPED");assertThat(f.posts()).hasSize(1);
    }

    @Test void aBlockedSdkCannotIgnoreTheActualTwoSecondCallCeiling() {
        var f=new Fixture();f.http.block=true;var start=System.nanoTime();var arm=f.arm();
        assertThatThrownBy(arm::arm).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(5));
        assertThat(f.posts()).isEmpty();assertThat(f.http.interrupted).isTrue();
    }

    @Test void oversizedDuplicateOrTrailingRawReplyIsRefusedBeforeAnyWrite() {
        for(String change:List.of("oversized","duplicate","trailing")){
            var f=new Fixture();f.http.mutate=body->switch(change){
                case "oversized"->body+" ".repeat(65537);
                case "duplicate"->body.replace("\"livemode\":false","\"livemode\":false,\"livemode\":false");
                default->body+" {}";
            };
            assertThatThrownBy(()->f.arm().arm()).hasMessage("FAILED_RENEWAL_STOPPED");assertThat(f.posts()).isEmpty();
        }
    }

    static UUID id(int n){return new UUID(0,n);}
    static final class Time extends Clock {
        final AtomicLong elapsed=new AtomicLong();final Instant start=Instant.parse("2026-11-15T12:00:00Z");
        public Instant instant(){return start.plusNanos(elapsed.get());}public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
    }
    static final class Fixture {
        final Time utc=new Time();final AtomicLong nanos=new AtomicLong();
        final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(id(1)),"owned",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_1UPiMpELswAd8tpR","stripe-sandbox-default");
        final SiteId site=new SiteId(id(2));final CustomerId customer=new CustomerId(id(3));
        final ClockOwner owner=new ClockOwner(id(4),id(5),account.id(),site,customer,account.externalAccountId(),account.credentialsRef(),StripeBillingTestClockAdapter.API_VERSION,
            1700000000L,"owned-arm","clock-create","a".repeat(64),utc.start.minusSeconds(100));
        final ReadyClock original=new ReadyClock(owner,id(6),"clock_owned",1700000000L,1800000000L,1801000000L,utc.start.minusSeconds(90),"b".repeat(64));
        final SubscriptionPriceTerms terms=new SubscriptionPriceTerms(new PriceId(id(7)),"monthly",1,new ProductId(id(8)),Money.of(2000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.INCLUSIVE);
        final OwnedClockSubscription intent=new OwnedClockSubscription(id(9),original,id(10),id(11),"cus_owned",id(12),id(13),"subscription:"+account.id().value()+":"+id(13)+":CREATE","v1.paid."+"d".repeat(64),0,1,terms,"price_owned","prod_owned","c".repeat(64),utc.start.minusSeconds(80));
        final OwnedClockSubscriptionReceipt receipt=new OwnedClockSubscriptionReceipt(intent,id(15),"sub_owned",42,"si_owned",1700000000L,1702592000L,utc.start.minusSeconds(70));
        final Admission admission=new Admission(id(16),receipt,"a".repeat(40),id(17),id(18),1,id(19),0,id(20),0,"in_initial","inpay_initial","pi_initial",2,1,"e".repeat(64),utc.start.minusSeconds(60));
        final Advance advance=new Advance(id(21),admission.id(),Step.BOUNDARY,receipt.periodEnd(),null,null,"billing-renewal-advance-v1:"+admission.id()+":1","f".repeat(64),utc.start.minusSeconds(50));
        final ProviderOperation operation=new ProviderOperation(id(22),account.id(),account.externalAccountId(),account.credentialsRef(),ProviderOperation.Kind.TEST_CLOCK_ADVANCE,advance.id(),advance.operationKey(),advance.fingerprint(),ProviderOperation.Status.ACKNOWLEDGED,"clock_owned",utc.start.minusSeconds(45),utc.start.plusSeconds(82800),null,null,2);
        final Ack ack=new Ack(advance,operation,"original-claim",utc.start.plusSeconds(10),new TestClockAck("clock_owned","test_helpers.test_clock",false,owner.name(),receipt.periodEnd(),"advancing",1800000000L,1801000000L,receipt.periodEnd()),utc.start.minusSeconds(40));
        final LaterReady ready=new LaterReady(id(23),admission.id(),ack,new TestClockReady("clock_owned","test_helpers.test_clock",false,owner.name(),receipt.periodEnd(),"ready",1800000000L,1801000000L,null),utc.start.minusSeconds(30));
        final Boundary boundary=new Boundary(id(24),admission.id(),ready,"si_owned","in_renewal",new Subscription.Period(Instant.ofEpochSecond(receipt.periodEnd()),Instant.ofEpochSecond(receipt.periodEnd()+2678400)),"b".repeat(64),utc.start.minusSeconds(20));
        final ReadClaim claim=new ReadClaim(ready,id(25),utc.start.minusSeconds(10),utc.start.plusSeconds(20),new ApplySubscriptionSnapshotUseCase.Fence(2,null,null,null,null,1L));
        final Pending pending=new Pending(admission,ready,boundary,claim,utc.start.minusSeconds(5));
        final Store store=new Store(this);final Transport http=new Transport(this);int contextReads;boolean foreignContext;
        OwnedBillingFailedRenewalFailureArm arm() {
            var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_INERT","pk_test_INERT","whsec_INERT"));
            var clients=new StripeClientFactory(){@Override public StripeClient create(StripeCredentials ignored){return StripeClient.builder().setApiKey("sk_test_INERT").setMaxNetworkRetries(0).setHttpClient(http).build();}};
            var accounts=new LoadPaymentAccountPort(){public Optional<PaymentAccount> findById(PaymentAccountId value){assertThat(value).isEqualTo(account.id());return Optional.of(account);}public Optional<PaymentAccount> findByKey(String value){throw new AssertionError("No arbitrary lookup");}};
            return new OwnedBillingFailedRenewalFailureArm(admission,pending,()->{contextReads++;return context();},credentials,clients,accounts,store,utc,utc.start.plusSeconds(120),nanos::get);
        }
        SubscriptionCommandContext context(){var session=new CustomerSessionContext(new CustomerSessionId(id(30)),site,foreignContext?new CustomerId(id(31)):customer,"https://shop.test",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE,CustomerSessionScope.SUBSCRIPTION_READ),utc.start.plusSeconds(780));return new SubscriptionCommandContext(new SubscriptionActor.Customer(session),site,session.customerId(),session.origin(),"arm-original",owner.run().toString());}
        List<StripeRequest> posts(){return http.calls.stream().filter(r->r.method().name().equals("POST")).toList();}
    }
    static final class Store implements OwnedFirstRenewalStore {
        final Fixture f;int readyReads,boundaryReads,stops;String higher;
        Store(Fixture f){this.f=f;}
        public Optional<LaterReady> ready(Admission a,Step step){assertThat(a).isSameAs(f.admission);assertThat(step).isEqualTo(Step.BOUNDARY);readyReads++;if(higher!=null)throw new IllegalStateException("highest-step-"+higher);return Optional.of(f.ready);}
        public Boundary boundary(Admission a){assertThat(a).isSameAs(f.admission);boundaryReads++;return f.boundary;}
        public Admission requireCurrent(Admission a){assertThat(a).isSameAs(f.admission);return a;}
        public void stop(Admission a,String reason,Instant at){stops++;}
        public Admission admitPaid(OwnedClockSubscriptionReceipt r,String s,Instant at){throw new AssertionError();}
        public Admission requirePaidInTransaction(Admission a){throw new AssertionError();}
        public Advance prepare(Admission a,Step s,Boundary b,Instant at){throw new AssertionError();}
        public LaterReady recordReady(Ack a,TestClockReady r,Instant at){throw new AssertionError();}
        public Boundary recordBoundary(Admission a,LaterReady r,Boundary b){throw new AssertionError();}
        public Publication recordPublicationInTransaction(Admission a,LaterReady r,Paid p){throw new AssertionError();}
        public Optional<Publication> publicationInTransaction(Admission a){throw new AssertionError();}
    }
    static final class Transport extends HttpClient {
        final Fixture f;final List<StripeRequest> calls=new ArrayList<>();UnaryOperator<String> mutate=UnaryOperator.identity();Runnable afterAttach;boolean block;volatile boolean interrupted;
        Transport(Fixture f){this.f=f;}
        @Override public StripeResponse request(StripeRequest request) {
            if(block)try{new CountDownLatch(1).await();throw new AssertionError("Unreachable");}catch(InterruptedException stopped){interrupted=true;Thread.currentThread().interrupt();throw new IllegalStateException("Private inert interruption");}
            calls.add(request);String path=request.url().getPath();boolean post=request.method().name().equals("POST");String body;
            if(path.endsWith("/attach")) {body="{\"id\":\"pm_attached\",\"object\":\"payment_method\",\"livemode\":false,\"type\":\"card\",\"customer\":\"cus_owned\",\"card\":{\"brand\":\"visa\",\"last4\":\"0341\"}}";if(afterAttach!=null)afterAttach.run();}
            else if(path.equals("/v1/test_helpers/test_clocks/clock_owned"))body="{\"id\":\"clock_owned\",\"object\":\"test_helpers.test_clock\",\"livemode\":false,\"name\":\"owned-arm\",\"frozen_time\":1702592000,\"created\":1800000000,\"deletes_after\":1801000000,\"status\":\"ready\",\"status_details\":{}}";
            else if(path.equals("/v1/customers/cus_owned"))body="{\"id\":\"cus_owned\",\"object\":\"customer\",\"livemode\":false,\"test_clock\":\"clock_owned\"}";
            else if(path.equals("/v1/subscriptions/sub_owned"))body="{\"id\":\"sub_owned\",\"object\":\"subscription\",\"livemode\":false,\"customer\":\"cus_owned\",\"test_clock\":\"clock_owned\",\"status\":\"active\",\"latest_invoice\":\"in_renewal\",\"default_payment_method\":\""+(post||f.posts().size()>1?"pm_attached":"pm_original")+"\"}";
            else if(path.equals("/v1/invoices/in_renewal"))body="{\"id\":\"in_renewal\",\"object\":\"invoice\",\"livemode\":false,\"customer\":\"cus_owned\",\"test_clock\":\"clock_owned\",\"status\":\"draft\",\"amount_paid\":0,\"amount_due\":2000,\"currency\":\"eur\"}";
            else throw new AssertionError("Unplanned inert request "+request.method()+" "+path);
            return new StripeResponse(200,HttpHeaders.of(Map.of()),mutate.apply(body));
        }
    }
}
