package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

/** Pure/inert: no superclass, extension, DataSource, application, network, Mockito or static runtime. */
class StripeBillingFirstRenewalAdvanceTest {
    private final StripeBillingInertSdkFixture f=new StripeBillingInertSdkFixture();
    private final Instant at=Instant.parse("2026-11-01T12:00:00Z");
    private final Clock utc=Clock.fixed(at,ZoneOffset.UTC);
    private ProviderOperation operation;
    private LaterReady saved;
    private boolean stopped;
    private RuntimeException authorityFailure;
    private int claims,acks,writes,unknownAttempts;
    private final List<String> stages=new ArrayList<>();
    private final List<Thread> stageThreads=new ArrayList<>();
    private String failAt;
    private final RuntimeException privateFailure=new IllegalStateException("private-provider-sentinel");
    private java.util.function.LongSupplier nanos=System::nanoTime;
    private Runnable afterReady=()->{};
    private void fail(String location){if(location.equals(failAt))throw privateFailure;}
    private BillingInterval interval=BillingInterval.MONTH;
    private long periodEnd=1700003600;
    private UUID id(int n){return new UUID(0,n);}
    private Admission admission() {
        var owner=f.admit();
        var original=new ReadyClock(owner,id(6),"clock_owned",1700000000,1800000000,1801000000,at.minusSeconds(50),"b".repeat(64));
        var terms=new SubscriptionPriceTerms(new PriceId(id(7)),"monthly",1,new ProductId(id(8)),Money.of(1000,"EUR"),interval,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        var intent=new OwnedClockSubscription(id(9),original,id(10),id(11),"cus_owned",id(12),id(13),"subscription:"+owner.account().value()+":"+id(13)+":CREATE",
            "v1.paid."+"d".repeat(64),0,1,terms,"price_owned","prod_owned","c".repeat(64),at.minusSeconds(10));
        var receipt=new OwnedClockSubscriptionReceipt(intent,id(15),"sub_owned",42,"si_owned",1700000000,periodEnd,at.minusSeconds(5));
        return new Admission(id(16),receipt,"a".repeat(40),id(17),id(18),1,id(19),0,id(20),0,"in_owned","inpay_owned","pi_owned",2,1,"e".repeat(64),at);
    }
    private StripeBillingFirstRenewalAdvanceAdapter adapter(Admission admission) {
        var advance=new Advance(id(21),admission.id(),Step.BOUNDARY,admission.receipt().periodEnd(),null,null,"billing-renewal-advance-v1:"+admission.id()+":1","f".repeat(64),at);
        var store=StripeBillingInertSdkFixture.port(OwnedFirstRenewalStore.class,call->{var a=call.args();return switch(call.name()){
            case "requireCurrent"->{fail("AUTHORITY");if(authorityFailure!=null)throw authorityFailure;if(stopped)throw new IllegalStateException("stopped");yield admission;}
            case "ready"->Optional.ofNullable(saved);
            case "prepare"->{fail("PREPARE");yield advance;}
            case "recordReady"->{fail("READY_STORE");writes++;saved=new LaterReady(id(22),admission.id(),(Ack)a[0],(TestClockReady)a[1],(Instant)a[2]);afterReady.run();yield saved;}
            case "stop"->{stopped=true;yield null;}
            default->throw new AssertionError("Unexpected owned call "+call.name());};});
        var steps=StripeBillingInertSdkFixture.port(ProviderOperationStore.class,call->{var a=call.args();return switch(call.name()){
            case "reserveOwnedFirstRenewalAdvance"->{fail("RESERVE");
                if(operation==null)operation=new ProviderOperation(id(23),f.account.id(),f.account.externalAccountId(),f.account.credentialsRef(),ProviderOperation.Kind.valueOf("TEST_CLOCK_ADVANCE"),
                    advance.id(),advance.operationKey(),advance.fingerprint(),ProviderOperation.Status.RESERVED,null,null,null,null,null,0);yield operation;}
            case "claim"->{fail("CLAIM");claims++;if(operation.firstDispatchedAt()!=null)yield Optional.empty();operation=StripeBillingInertSdkFixture.copy(operation,ProviderOperation.Status.DISPATCHING,null,at,at.plusSeconds(82800),(String)a[1],at.plusSeconds(60),1);yield Optional.of(operation);}
            case "acknowledgeOwnedFirstRenewalAdvance"->{fail("ACK_STORE");acks++;var claim=operation;operation=StripeBillingInertSdkFixture.copy(operation,ProviderOperation.Status.ACKNOWLEDGED,"clock_owned",at,at.plusSeconds(82800),null,null,2);yield new Ack(advance,operation,claim.leaseOwner(),claim.leaseExpiresAt(),(TestClockAck)a[2],(Instant)a[3]);}
            case "findById"->Optional.ofNullable(operation);
            case "unknown"->{unknownAttempts++;
                if(operation.status()!=ProviderOperation.Status.DISPATCHING||!Objects.equals(operation.leaseOwner(),a[1])||operation.version()!=(long)a[2])throw new IllegalStateException("inert lost claim");
                operation=StripeBillingInertSdkFixture.copy(operation,ProviderOperation.Status.UNKNOWN,null,at,at.plusSeconds(82800),null,null,2);yield operation;}
            default->throw new AssertionError("Unexpected operation call "+call.name());};});
        var accounts=StripeBillingInertSdkFixture.port(dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort.class,call->{fail("ACCOUNT");return Optional.of(f.account);});
        var clients=new dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory(){
            @Override public com.stripe.StripeClient create(dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentials credentials){fail("CLIENT");return f.clients.create(credentials);}
        };
        return new StripeBillingFirstRenewalAdvanceAdapter(f.credentials,clients,accounts,steps,store,utc,nanos,stage->{stages.add(stage);stageThreads.add(Thread.currentThread());});
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"AUTHORITY","PREPARE","ACCOUNT","RESERVE","CLAIM","CLIENT","ACK_STORE","READY_STORE"})
    void localFailureKeepsLastSourceCheckpointWithoutLeakingOrReopeningDispatch(String location) {
        var admission=admission();var adapter=adapter(admission);failAt=location;
        f.http.reply(clock(1700000000,"ready"));f.http.reply(clock(1700000000,"advancing"));f.http.reply(clock(1700003600,"ready"));
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isSameAs(privateFailure);
        assertThat(stages).last().isEqualTo("CLOCK_ADVANCE_"+location);
        assertThat(stages).allMatch(stage->!stage.contains("sentinel"));
        if(Set.of("CLIENT","ACK_STORE","READY_STORE").contains(location)) {
            assertThat(stopped).isTrue();assertThat(unknownAttempts).isEqualTo(1);
            assertThat(operation.status()).isEqualTo(location.equals("READY_STORE")?ProviderOperation.Status.ACKNOWLEDGED:ProviderOperation.Status.UNKNOWN);
            int calls=f.http.calls.size();failAt=null;
            assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
            assertThat(f.http.calls).hasSize(calls);assertThat(claims).isEqualTo(1);
        } else {assertThat(f.http.calls).isEmpty();assertThat(claims).isZero();}
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PRE_READ","PRE_VALIDATE","POST","POST_VALIDATE","POLL_READ","POLL_VALIDATE","POLL_EXHAUSTED"})
    void remoteFailureKeepsBoundedCheckpointAndConsumesAuthority(String location) {
        var admission=admission();var adapter=adapter(admission);
        if(location.equals("PRE_READ"))f.http.disconnect();
        else f.http.reply(clock(location.equals("PRE_VALIDATE")?1700000001:1700000000,"ready"));
        if(location.equals("POST"))f.http.disconnect();
        else f.http.reply(clock(location.equals("POST_VALIDATE")?1700003601:1700000000,"advancing"));
        if(location.equals("POLL_READ"))f.http.disconnect();
        else if(location.equals("POLL_EXHAUSTED"))for(int i=0;i<12;i++)f.http.reply(clock(1700000000,"advancing"));
        else f.http.reply(clock(location.equals("POLL_VALIDATE")?1700003601:1700003600,"ready"));
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(stages).last().isEqualTo("CLOCK_ADVANCE_"+location);
        assertThat(stopped).isTrue();assertThat(unknownAttempts).isEqualTo(1);
        assertThat(operation.status()).isEqualTo(location.startsWith("POLL_")?ProviderOperation.Status.ACKNOWLEDGED:ProviderOperation.Status.UNKNOWN);
        assertThat(writes).isZero();int calls=f.http.calls.size();
        assertThat(f.http.calls.stream().filter(c->c.method().name().equals("POST")).count()).isEqualTo(location.startsWith("PRE_")?0:1);
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(f.http.calls).hasSize(calls);assertThat(claims).isEqualTo(1);
    }
    @Test void completedReadyAfterInvocationBudgetCannotReturnToGraphOrRegainAuthority() {
        var elapsed=new java.util.concurrent.atomic.AtomicLong();nanos=elapsed::get;
        afterReady=()->elapsed.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(31));
        var admission=admission();var adapter=adapter(admission);
        f.http.reply(clock(1700000000,"ready"));f.http.reply(clock(1700000000,"advancing"));f.http.reply(clock(1700003600,"ready"));
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY))
            .as("READY store completed at 31s, while original 60s lease remains valid").isInstanceOf(RuntimeException.class);
        assertThat(saved).as("A completed store write is not rolled back by the return check").isNotNull();
        assertThat(writes).isEqualTo(1);assertThat(acks).isEqualTo(1);assertThat(stopped).isTrue();
        assertThat(unknownAttempts).isEqualTo(1);
        assertThat(operation.status()).as("Committed ACK cannot be rewritten with the original consumed claim").isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(stages).last().isEqualTo("CLOCK_ADVANCE_READY_STORE");
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(f.http.calls).hasSize(3);assertThat(claims).isEqualTo(1);assertThat(writes).isEqualTo(1);
    }
    private String clock(long frozen,String status) {
        var json=com.google.gson.JsonParser.parseString(f.clockJson()).getAsJsonObject();json.addProperty("frozen_time",frozen);json.addProperty("status",status);return json.toString();
    }
    @ParameterizedTest @CsvSource({"MONTH,63","YEAR,732"})
    void overTwoShortestIntervalsRefusesBeforeDispatch(BillingInterval selected,long days) {
        interval=selected;periodEnd=1700000000+days*86400;
        var admission=admission();var adapter=adapter(admission);
        // Complete otherwise-valid responses: an empty transport cannot manufacture refusal.
        f.http.reply(clock(1700000000,"ready"));f.http.reply(clock(1700000000,"advancing"));f.http.reply(clock(periodEnd,"ready"));
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY))
            .as("A target beyond two %s intervals must be refused before consuming dispatch",selected).isInstanceOf(RuntimeException.class);
        assertThat(f.http.calls).isEmpty();assertThat(claims).isZero();assertThat(acks).isZero();assertThat(writes).isZero();
    }
    @Test void authenticatedPreclaimDriftStopsWithoutProviderIo() {
        var admission=admission();var adapter=adapter(admission);authorityFailure=new AuthorityDrift();
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(stopped).as("Authenticated preclaim authority drift must terminally stop the admission").isTrue();
        authorityFailure=null;
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(f.http.calls).isEmpty();assertThat(claims).isZero();
    }
    @Test void infrastructureFailureDoesNotGainAuthorityToStopAdmission() {
        var admission=admission();var adapter=adapter(admission);
        authorityFailure=new org.springframework.dao.InvalidDataAccessApiUsageException("inert infrastructure failure");
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isSameAs(authorityFailure);
        assertThat(stopped).isFalse();assertThat(f.http.calls).isEmpty();assertThat(claims).isZero();
    }
    @Test void postsOnceAndPersistsOnlySeparateGetReady() {
        var admission=admission();var adapter=adapter(admission);
        f.http.reply(clock(1700000000,"ready"));f.http.reply(clock(1700000000,"advancing"));f.http.reply(clock(1700003600,"ready"));
        var result=new AtomicReference<LaterReady>();
        assertThatCode(()->result.set(adapter.advance(admission,Step.BOUNDARY)))
            .as("Owned advance must dispatch exactly once and accept later exact GET READY")
            .doesNotThrowAnyException();
        assertThat(result.get()).isNotNull();assertThat(result.get().observation().frozenTime()).isEqualTo(1700003600);
        assertThat(result.get().ack().observation().frozenTime()).isEqualTo(1700000000);
        assertThat(f.http.calls).hasSize(3);assertThat(claims).isEqualTo(1);assertThat(acks).isEqualTo(1);assertThat(writes).isEqualTo(1);
        var post=f.http.calls.get(1);assertThat(post.method().name()).isEqualTo("POST");
        assertThat(post.url().getPath()).isEqualTo("/v1/test_helpers/test_clocks/clock_owned/advance");
        assertThat(post.params()).containsOnlyKeys("frozen_time").containsEntry("frozen_time",1700003600L);
        assertThat(post.headers().map().get("Idempotency-Key")).containsExactly("billing-renewal-advance-v1:"+admission.id()+":1");
        for(var request:f.http.calls){assertThat(request.options().getMaxNetworkRetries()).isZero();
            assertThat(request.options().getConnectTimeout()).isBetween(1,2000);assertThat(request.options().getReadTimeout()).isBetween(1,2000);
            assertThat(request.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");}
        assertThat(adapter.advance(admission,Step.BOUNDARY)).isEqualTo(result.get());assertThat(f.http.calls).hasSize(3);
        assertThat(stopped).isFalse();
        assertThat(stageThreads).allMatch(thread->thread==Thread.currentThread());
        assertThat(stages).containsExactly("CLOCK_ADVANCE_AUTHORITY","CLOCK_ADVANCE_PREPARE","CLOCK_ADVANCE_ACCOUNT",
            "CLOCK_ADVANCE_RESERVE","CLOCK_ADVANCE_CLAIM","CLOCK_ADVANCE_CLIENT","CLOCK_ADVANCE_PRE_READ","CLOCK_ADVANCE_PRE_VALIDATE",
            "CLOCK_ADVANCE_POST","CLOCK_ADVANCE_POST_VALIDATE","CLOCK_ADVANCE_ACK_STORE","CLOCK_ADVANCE_POLL_WAIT",
            "CLOCK_ADVANCE_POLL_READ","CLOCK_ADVANCE_POLL_VALIDATE","CLOCK_ADVANCE_READY_STORE","CLOCK_ADVANCE_AUTHORITY");
    }
    @Test void ambiguousPostConsumesDispatchAndRerunMakesNoCalls() {
        var admission=admission();var adapter=adapter(admission);
        f.http.reply(clock(1700000000,"ready"));f.http.disconnect();
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(claims).isEqualTo(1);assertThat(acks).isZero();assertThat(writes).isZero();assertThat(stopped).isTrue();
        assertThat(operation.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        assertThat(f.http.calls).hasSize(2);
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(f.http.calls).hasSize(2);assertThat(claims).isEqualTo(1);
    }
    @Test void contradictoryPostTargetCannotBecomeAck() {
        var admission=admission();var adapter=adapter(admission);
        f.http.reply(clock(1700000000,"ready"));
        var post=com.google.gson.JsonParser.parseString(clock(1700000000,"advancing")).getAsJsonObject();
        post.add("status_details",com.google.gson.JsonParser.parseString("{\"advancing\":{\"target_frozen_time\":1700003601}}"));
        f.http.reply(post.toString());
        assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(f.http.calls).hasSize(2);assertThat(acks).isZero();assertThat(writes).isZero();assertThat(stopped).isTrue();
    }
    @Test void sdkDeadlineDoesNotWaitForUncooperativeWorkerOrPersistLateResult() throws Exception {
        var admission=admission();var adapter=adapter(admission);
        var release=new java.util.concurrent.CountDownLatch(1);var finished=new java.util.concurrent.CountDownLatch(1);
        f.http.reply(clock(1700000000,"ready"));
        f.http.afterResponse=()->{
            boolean released=false;
            while(!released)try{release.await();released=true;}catch(InterruptedException ignored){}
            finished.countDown();
        };
        long began=System.nanoTime();
        try {
            assertThatThrownBy(()->adapter.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime()-began)).isLessThan(Duration.ofSeconds(5));
            assertThat(acks).isZero();assertThat(writes).isZero();assertThat(stopped).isTrue();
        } finally {release.countDown();}
        assertThat(finished.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(acks).isZero();assertThat(writes).isZero();assertThat(f.http.calls).hasSize(1);
    }
}
