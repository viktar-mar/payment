package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalMappingTest.*;
import static dev.genericpayments.platform.support.StripeBillingInertSdkFixture.port;
import static org.assertj.core.api.Assertions.*;

/** Pure consumer over handwritten lifecycle ports and the audited inert official SDK transport. */
class StripeBillingFirstRenewalObservationTest {
    private final StripeBillingOwnedSubscriptionFixture f=paidLocal();
    private final Admission admission=admission(f);
    private LaterReady ready=boundaryReady(admission,f.utc.plusSeconds(4));
    private final List<String> locks=new ArrayList<>();
    private final Instant now=f.utc.plusSeconds(10);
    private boolean inTransaction;
    private ReadClaim claim;
    private Boundary saved;
    private int captures,stops,inserts;
    private long nanos;
    private String reason;
    private boolean delayLock;
    private boolean foreignInvocation;
    private final List<Duration> waits=new ArrayList<>();
    private final List<String> graphStages=new ArrayList<>();
    private final OwnedFirstRenewalStore store=port(OwnedFirstRenewalStore.class,call->switch(call.name()){
        case "requirePaidInTransaction"->{assertThat(inTransaction).isTrue();assertThat(call.args()[0]).isEqualTo(admission);yield admission;}
        case "requireReadyInTransaction"->{assertThat(inTransaction).isTrue();assertThat(call.args()[1]).isEqualTo(ready);yield ready;}
        case "beginReadInTransaction"->{
            assertThat(inTransaction).isTrue();assertThat(locks).containsExactly("operation","site/customer","account","subscription");
            if(claim!=null)throw new IllegalStateException("Already consumed");inserts++;
            claim=new ReadClaim((LaterReady)call.args()[1],foreignInvocation?new UUID(0,99):(UUID)call.args()[3],(Instant)call.args()[4],((Instant)call.args()[4]).plusSeconds(30),(Fence)call.args()[2]);yield claim;
        }
        case "readClaim"->{assertThat(inTransaction).isTrue();yield Optional.ofNullable(claim);}
        case "boundaryInTransaction"->{assertThat(inTransaction).isTrue();yield saved;}
        case "recordBoundary"->{assertThat(inTransaction).isTrue();saved=(Boundary)call.args()[2];yield saved;}
        case "stop"->{assertThat(inTransaction).isFalse();stops++;reason=(String)call.args()[1];yield null;}
        default->throw new AssertionError("Unexpected renewal call "+call.name());
    });
    private StripeBillingFirstRenewalAdapter adapter(){
        var tx=port(SubscriptionTransactionPort.class,call->{
            if(!call.name().equals("required"))throw new AssertionError(call.name());
            assertThat(inTransaction).isFalse();inTransaction=true;locks.clear();
            try{return ((Supplier<?>)call.args()[0]).get();}finally{inTransaction=false;}
        });
        var operations=port(SubscriptionOperationStore.class,call->switch(call.name()){
            case "unresolved"->List.of();case "lock"->{locks.add("operation");yield f.operation;}
            default->throw new AssertionError(call.name());});
        SubscriptionLifecycleLockPort lifecycle=(site,customer)->{assertThat(site).isEqualTo(f.f.site);assertThat(customer).isEqualTo(f.f.customer);locks.add("site/customer");};
        LockPaymentAccountPort accounts=id->{locks.add("account");if(delayLock)nanos+=Duration.ofSeconds(31).toNanos();return Optional.of(f.f.account);};
        var subscriptions=port(SubscriptionStore.class,call->switch(call.name()){
            case "findOwned"->Optional.of(f.local);case "lock"->{locks.add("subscription");yield f.local;}
            default->throw new AssertionError(call.name());});
        var observations=port(SubscriptionObservationPort.class,call->{assertThat(call.name()).isEqualTo("captureFence");captures++;return 7L;});
        var bindings=port(StripeBillingBindingPort.class,call->{assertThat(call.name()).isEqualTo("findCustomer");return Optional.of(f.binding());});
        return new StripeBillingFirstRenewalAdapter(f.f.credentials,f.f.clients,store,tx,operations,lifecycle,accounts,subscriptions,
            subscription->null,observations,bindings,Clock.fixed(now,ZoneOffset.UTC),()->nanos,duration->{waits.add(duration);nanos+=duration.toNanos();},graphStages::add);
    }
    private void graph(){
        graph(false);
    }
    private void graph(boolean paid){
        var json=StripeBillingOwnedSubscriptionFixture.json(f.f.clockJson());json.addProperty("frozen_time",ready.observation().frozenTime());
        f.f.http.reply(json.toString());queueGraph(f,paid);f.f.http.reply(json.toString());
    }
    private void finalization(){
        saved=new Boundary(new UUID(0,24),admission.id(),ready,"si_owned","in_renewal",new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(
            Instant.ofEpochSecond(1702678400),Instant.ofEpochSecond(1705356800)),"b".repeat(64),f.utc.plusSeconds(5));
        ready=finalReady(admission,saved);
    }
    @Test void boundaryObserverClaimsBeforeIoAndRetainsCapturedFence(){
        graph();f.f.http.afterResponse=()->{assertThat(claim).isNotNull();assertThat(inTransaction).isFalse();};
        var result=new AtomicReference<Observation>();
        assertThatCode(()->result.set(adapter().observe(admission,ready)))
            .as("The real observer must insert a read claim before the complete boundary graph and return private Pending").doesNotThrowAnyException();
        assertThat(result.get()).isInstanceOf(Pending.class);assertThat(saved).isEqualTo(((Pending)result.get()).boundary());
        assertThat(((Pending)result.get()).claim()).isEqualTo(claim);assertThat(((Pending)result.get()).graphAcceptedAt()).isEqualTo(now);
        assertThat(inserts).isEqualTo(1);assertThat(captures).isEqualTo(1);assertThat(stops).isZero();
        assertThat(claim.fence()).isEqualTo(new Fence(2,null,null,null,null,7L));
        assertThat(f.f.http.calls).hasSize(12);assertThat(f.f.http.replies).isEmpty();
        assertThat(graphStages).containsExactly("GRAPH_CLAIM","GRAPH_CLOCK_BEFORE","GRAPH_REMOTE_PRECHECK","GRAPH_CUSTOMER",
            "GRAPH_SUBSCRIPTION","GRAPH_INVOICES","GRAPH_INITIAL_INVOICE","GRAPH_RENEWAL_INVOICE","GRAPH_CLOCK_AFTER","GRAPH_ACCEPT");
        for(var request:f.f.http.calls){assertThat(request.options().getReadTimeout()).isBetween(1,2000);assertThat(request.options().getConnectTimeout()).isBetween(1,2000);assertThat(request.options().getMaxNetworkRetries()).isZero();}
        assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);
        assertThat(f.f.http.calls).hasSize(12);assertThat(stops).isZero();assertThat(inserts).isEqualTo(1);
    }
    @Test void finalPendingExhaustsThreeAttemptsInOneClaim(){
        finalization();graph();graph();graph();
        assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);
        assertThat(inserts).isEqualTo(1);assertThat(captures).isEqualTo(1);assertThat(f.f.http.calls).hasSize(36);
        assertThat(waits).containsExactly(Duration.ofMillis(250),Duration.ofMillis(250));assertThat(reason).isEqualTo("PAYMENT_NOT_OBSERVED");
        assertThat(stops).isEqualTo(1);assertThat(f.f.http.replies).isEmpty();
    }
    @Test void finalPaidRetainsOriginalClaimAndFence(){
        finalization();graph(true);var result=new AtomicReference<Observation>();
        assertThatCode(()->result.set(adapter().observe(admission,ready))).doesNotThrowAnyException();
        assertThat(result.get()).isInstanceOf(Paid.class);var paid=(Paid)result.get();
        assertThat(paid.claim()).isEqualTo(claim);assertThat(paid.fence()).isEqualTo(claim.fence());assertThat(paid.boundary()).isEqualTo(saved);
        assertThat(paid.graphAcceptedAt()).isEqualTo(now);assertThat(captures).isEqualTo(1);assertThat(inserts).isEqualTo(1);assertThat(stops).isZero();
        assertThat(f.f.http.calls).hasSize(15);
    }
    @Test void lockWaitConsumesOriginalBudgetBeforeAnyRequest(){
        delayLock=true;graph();assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);
        assertThat(inserts).isZero();assertThat(f.f.http.calls).isEmpty();assertThat(saved).isNull();assertThat(stops).isZero();
    }
    @Test void reentrantLoserDoesNotStopWinner(){
        graph();f.f.http.afterResponse=()->assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);
        assertThat(adapter().observe(admission,ready)).isInstanceOf(Pending.class);
        assertThat(inserts).isEqualTo(1);assertThat(stops).isZero();assertThat(f.f.http.calls).hasSize(12);
    }
    @Test void crashResidueGetsNoNewClaimOrBudget(){
        claim=new ReadClaim(ready,UUID.randomUUID(),now.minusSeconds(1),now.plusSeconds(29),new Fence(2,null,null,null,null,7L));graph();
        assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);
        assertThat(inserts).isZero();assertThat(stops).isZero();assertThat(f.f.http.calls).isEmpty();
    }
    @Test void changedClaimCannotReachBoundary(){
        graph();f.f.http.afterResponse=()->claim=new ReadClaim(ready,UUID.randomUUID(),claim.startedAt(),claim.deadlineAt(),claim.fence());
        assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);
        assertThat(saved).isNull();assertThat(f.f.http.calls).hasSize(1);assertThat(reason).isEqualTo("AUTHORITY_DRIFT");
        assertThat(graphStages.getLast()).isEqualTo("GRAPH_CLOCK_BEFORE");
    }
    @Test void foreignInvocationClaimCannotStartIo(){
        foreignInvocation=true;graph();
        assertThatThrownBy(()->adapter().observe(admission,ready)).as("A returned claim must identify this exact original invocation, not merely the same READY and timestamps").isInstanceOf(RuntimeException.class);
        assertThat(f.f.http.calls).isEmpty();assertThat(stops).isZero();
    }
    @Test void lateClosingClockCannotPersistBoundary(){
        graph();int[] reads={0};Runnable[] callback=new Runnable[1];callback[0]=()->{if(++reads[0]==12)nanos=Duration.ofSeconds(31).toNanos();else f.f.http.afterResponse=callback[0];};f.f.http.afterResponse=callback[0];
        assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);
        assertThat(f.f.http.calls).hasSize(12);assertThat(saved).isNull();assertThat(reason).isEqualTo("PAYMENT_NOT_OBSERVED");
    }
    @Test void sdkCallOverTwoSecondsCannotPersistBoundary(){
        graph();f.f.http.afterResponse=()->nanos=Duration.ofSeconds(2).toNanos();
        assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);
        assertThat(f.f.http.calls).hasSize(1);assertThat(saved).isNull();assertThat(reason).isEqualTo("PAYMENT_NOT_OBSERVED");
    }
    @Test void interruptionIgnoringSdkCannotPersistAfterCallerTimeout() throws Exception {
        graph();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var finished=new java.util.concurrent.CountDownLatch(1);
        f.f.http.afterResponse=()->{
            entered.countDown();long began=System.nanoTime();
            try{while(release.getCount()!=0){
                if(System.nanoTime()-began>Duration.ofSeconds(6).toNanos())throw new AssertionError("Inert worker release timed out");
                try{release.await(100,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){/* Simulates an uncooperative SDK transport only. */}
            }}finally{finished.countDown();}
        };
        long began=System.nanoTime();
        try{
            assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);
            assertThat(entered.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime()-began)).isLessThan(Duration.ofSeconds(4));
            assertThat(finished.getCount()).isEqualTo(1);assertThat(saved).isNull();assertThat(reason).isEqualTo("PAYMENT_NOT_OBSERVED");
        }finally{release.countDown();assertThat(finished.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
        assertThat(saved).isNull();assertThat(inserts).isEqualTo(1);assertThat(stops).isEqualTo(1);assertThat(f.f.http.calls).hasSize(1);
        assertThatThrownBy(()->adapter().observe(admission,ready)).isInstanceOf(RuntimeException.class);assertThat(f.f.http.calls).hasSize(1);
    }
}
