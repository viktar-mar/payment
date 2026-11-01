package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.subscriptions.application.port.in.PrepareSubscriptionReadUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import com.stripe.net.RequestOptions;
import com.stripe.model.testhelpers.TestClock;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit private consumer; deliberately absent from ordinary provider registration. */
public final class StripeBillingFirstRenewalAdapter {
    private final StripeCredentialResolver credentials;private final StripeClientFactory clients;private final OwnedFirstRenewalStore store;
    private final SubscriptionTransactionPort transaction;private final SubscriptionOperationStore operations;private final SubscriptionLifecycleLockPort lifecycle;
    private final LockPaymentAccountPort accounts;private final SubscriptionStore subscriptions;private final PrepareSubscriptionReadUseCase preparation;
    private final SubscriptionObservationPort observations;private final StripeBillingBindingPort bindings;private final Clock utc;
    private final LongSupplier nanos;private final Consumer<Duration> wait;
    public StripeBillingFirstRenewalAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            OwnedFirstRenewalStore store,SubscriptionTransactionPort transaction,SubscriptionOperationStore operations,
            SubscriptionLifecycleLockPort lifecycle,LockPaymentAccountPort accounts,SubscriptionStore subscriptions,
            PrepareSubscriptionReadUseCase preparation,SubscriptionObservationPort observations,
            StripeBillingBindingPort bindings,Clock utc) {
        this(credentials,clients,store,transaction,operations,lifecycle,accounts,subscriptions,preparation,observations,bindings,
            utc,System::nanoTime,duration->{try{Thread.sleep(duration);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}});
    }
    StripeBillingFirstRenewalAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            OwnedFirstRenewalStore store,SubscriptionTransactionPort transaction,SubscriptionOperationStore operations,
            SubscriptionLifecycleLockPort lifecycle,LockPaymentAccountPort accounts,SubscriptionStore subscriptions,
            PrepareSubscriptionReadUseCase preparation,SubscriptionObservationPort observations,
            StripeBillingBindingPort bindings,Clock utc,LongSupplier nanos,Consumer<Duration> wait) {
        this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);this.store=Objects.requireNonNull(store);
        this.transaction=Objects.requireNonNull(transaction);this.operations=Objects.requireNonNull(operations);this.lifecycle=Objects.requireNonNull(lifecycle);
        this.accounts=Objects.requireNonNull(accounts);this.subscriptions=Objects.requireNonNull(subscriptions);this.preparation=Objects.requireNonNull(preparation);
        this.observations=Objects.requireNonNull(observations);this.bindings=Objects.requireNonNull(bindings);this.utc=Objects.requireNonNull(utc);
        this.nanos=Objects.requireNonNull(nanos);this.wait=Objects.requireNonNull(wait);
    }
    public Observation observe(Admission admission,LaterReady ready) {
        if(TransactionSynchronizationManager.isActualTransactionActive()||admission==null||ready==null)throw unavailable();
        long began=nanos.getAsLong();Instant startedAt=utc.instant().truncatedTo(ChronoUnit.MICROS);
        var budget=new Budget(began,startedAt.plusSeconds(30));
        // Outside the winner catch: a conflict, forged scope or crash residue never stops the live owner.
        var start=transaction.required(()->{
            var state=locked(admission,ready);budget.remaining();
            var boundary=ready.ack().advance().step()==Step.FINALIZATION?store.boundaryInTransaction(admission):null;
            var selected=state.selected();
            var fence=new Fence(state.subscription().revision(),selected==null?null:selected.id(),selected==null?null:selected.version(),null,null,
                observations.captureFence(state.subscription()));
            var invocation=UUID.randomUUID();var claim=store.beginReadInTransaction(admission,ready,fence,invocation,startedAt);
            if(!claim.invocation().equals(invocation)||!claim.ready().equals(ready)||!claim.startedAt().equals(startedAt)
                ||!claim.deadlineAt().equals(startedAt.plusSeconds(30))||!claim.fence().equals(fence))throw unavailable();
            return new Start(admission,ready,state,claim,boundary);
        });
        try{
            budget.remaining();var client=clients.create(credentials.resolve(start.state().account()));
            int attempts=ready.ack().advance().step()==Step.BOUNDARY?1:3;
            for(int attempt=0;attempt<attempts;attempt++){
                if(attempt>0){if(budget.remaining()<=Duration.ofMillis(250).toNanos())throw new NotObserved();wait.accept(Duration.ofMillis(250));budget.remaining();}
                var executor=new Attempt(start,budget);
                exactClock(executor.execute(()->client.v1().testHelpers().testClocks().retrieve(admission.receipt().intent().clock().reference(),executor.options())),ready);
                var intent=admission.receipt().intent();
                var ownership=new StripeBillingSnapshotMapper.Ownership(start.state().account(),start.state().subscription(),start.state().binding(),
                    List.of(new StripeBillingSnapshotMapper.PublishedPrice(intent.terms(),intent.priceReference(),intent.productReference())));
                var mapped=StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(client,ownership,admission,ready,
                    utc.instant().truncatedTo(ChronoUnit.MICROS),start.boundary(),executor);
                exactClock(executor.execute(()->client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),executor.options())),ready);
                revalidate(start);budget.remaining();var acceptedAt=utc.instant().truncatedTo(ChronoUnit.MICROS);
                if(ready.ack().advance().step()==Step.BOUNDARY){
                    if(!(mapped instanceof MappedPending pending))throw unavailable();
                    var b=pending.boundary();
                    var boundary=new Boundary(b.id(),admission.id(),ready,b.item(),b.renewalInvoice(),b.period(),
                        OwnedFirstRenewal.fingerprint("gp:owned-first-renewal-boundary:v1",b.id(),admission.id(),ready.id(),b.item(),b.renewalInvoice(),b.period().start(),b.period().end(),acceptedAt),acceptedAt);
                    var saved=transaction.required(()->{validateLocked(start);budget.remaining();return store.recordBoundary(admission,ready,boundary);});
                    budget.remaining();return new Pending(admission,ready,saved,start.claim(),acceptedAt);
                }
                if(mapped instanceof MappedPaid paid)return new Paid(admission,ready,start.boundary(),start.claim().fence(),paid.snapshot(),start.claim(),acceptedAt);
            }
            throw new NotObserved();
        }catch(RuntimeException failure){
            String reason=failure instanceof NotObserved?"PAYMENT_NOT_OBSERVED":failure instanceof AuthorityDrift?"AUTHORITY_DRIFT":"GRAPH_UNSUPPORTED";
            try{store.stop(admission,reason,utc.instant());}catch(RuntimeException ignored){}
            throw failure;
        }
    }
    private record State(PaymentAccount account,Subscription subscription,SubscriptionOperation selected,StripeBillingBindingPort.CustomerBinding binding){}
    private record Start(Admission admission,LaterReady ready,State state,ReadClaim claim,Boundary boundary){}
    private State locked(Admission admission,LaterReady ready){
        var intent=admission.receipt().intent();var owner=intent.clock().owner();
        var candidate=subscriptions.findOwned(owner.account(),intent.subscription()).orElseThrow(StripeBillingFirstRenewalAdapter::unavailable);
        if(!candidate.siteId().equals(owner.site())||!candidate.customerId().equals(owner.customer()))throw unavailable();
        var ids=new TreeSet<UUID>();ids.add(intent.createOperation());operations.unresolved(candidate.siteId(),candidate.id()).forEach(o->ids.add(o.id()));
        for(var id:ids)operations.lock(id);
        lifecycle.lockActive(owner.site(),owner.customer());
        var account=accounts.findByIdForUpdate(owner.account()).orElseThrow(StripeBillingFirstRenewalAdapter::unavailable);
        var subscription=subscriptions.lock(owner.site(),intent.subscription());
        if(!admission.equals(store.requirePaidInTransaction(admission))||!ready.equals(store.requireReadyInTransaction(admission,ready)))throw unavailable();
        var binding=bindings.findCustomer(owner.account(),owner.site(),owner.customer()).orElseThrow(StripeBillingFirstRenewalAdapter::unavailable);
        return new State(account,subscription,preparation.financial(subscription),binding);
    }
    private void revalidate(Start start){transaction.required(()->{validateLocked(start);return null;});}
    private void validateLocked(Start start){
        var current=locked(start.admission(),start.ready());
        if(!current.equals(start.state())||!store.readClaim(start.admission(),start.ready()).filter(start.claim()::equals).isPresent())throw new AuthorityDrift();
        if(start.boundary()!=null&&!start.boundary().equals(store.boundaryInTransaction(start.admission())))throw new AuthorityDrift();
    }
    private static void exactClock(TestClock remote,LaterReady expected){
        var value=expected.observation();
        if(remote==null||!value.reference().equals(remote.getId())||!"test_helpers.test_clock".equals(remote.getObject())||!Boolean.FALSE.equals(remote.getLivemode())
            ||Boolean.TRUE.equals(remote.getDeleted())||!value.name().equals(remote.getName())||!Objects.equals(value.frozenTime(),remote.getFrozenTime())
            ||!Objects.equals(value.created(),remote.getCreated())||!Objects.equals(value.deletesAfter(),remote.getDeletesAfter())||!"ready".equals(remote.getStatus())
            ||remote.getStatusDetails()!=null&&remote.getStatusDetails().getAdvancing()!=null
                &&!Objects.equals(value.frozenTime(),remote.getStatusDetails().getAdvancing().getTargetFrozenTime()))throw unavailable();
    }
    private final class Budget {
        private final long began;private final Instant deadline;
        Budget(long began,Instant deadline){this.began=began;this.deadline=deadline;}
        long remaining(){
            long remaining=Math.min(Duration.ofSeconds(30).toNanos()-(nanos.getAsLong()-began),Duration.between(utc.instant(),deadline).toNanos());
            if(Thread.currentThread().isInterrupted()||remaining<=0)throw new NotObserved();return remaining;
        }
    }
    private final class Attempt implements StripeBillingSnapshotMapper.ReadExecutor {
        private final Start start;private final Budget budget;private int requests;private RequestOptions options;
        Attempt(Start start,Budget budget){this.start=start;this.budget=budget;}
        public RequestOptions options(){return options;}
        public <T>T execute(StripeBillingSnapshotMapper.Call<T> call){
            if(++requests>100)throw unavailable();revalidate(start);
            long allowance=Math.min(Duration.ofSeconds(2).toNanos(),budget.remaining());
            int millis=(int)TimeUnit.NANOSECONDS.toMillis(allowance);if(millis<=0)throw new NotObserved();
            var builder=RequestOptions.builder().setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
            RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,StripeBillingTestClockAdapter.API_VERSION);options=builder.build();
            long actualStart=System.nanoTime();long monotonicStart=nanos.getAsLong();
            // Only the complete SDK call enters the worker; all claim/store/acceptance work stays here.
            var pending=new FutureTask<T>(()->{if(Thread.currentThread().isInterrupted()||System.nanoTime()-actualStart>=allowance)throw unavailable();return call.run();});
            Thread.ofVirtual().name("owned-renewal-read").start(pending);
            try{
                long remaining=Math.min(allowance-(System.nanoTime()-actualStart),budget.remaining());if(remaining<=0)throw new NotObserved();
                var result=pending.get(remaining,TimeUnit.NANOSECONDS);
                if(System.nanoTime()-actualStart>=allowance||nanos.getAsLong()-monotonicStart>=allowance)throw new NotObserved();
                budget.remaining();revalidate(start);budget.remaining();return result;
            }catch(InterruptedException failure){Thread.currentThread().interrupt();throw unavailable();}
            catch(TimeoutException failure){throw new NotObserved();}
            catch(ExecutionException failure){if(failure.getCause() instanceof Error error)throw error;throw unavailable();}
            finally{pending.cancel(true);}
        }
    }
    private static final class NotObserved extends RuntimeException {}
    private static IllegalStateException unavailable(){return new IllegalStateException("Owned first renewal graph unavailable");}
}
