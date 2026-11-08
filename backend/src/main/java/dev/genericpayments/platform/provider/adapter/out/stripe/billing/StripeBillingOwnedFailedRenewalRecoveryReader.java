package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Explicit read-only original-debt preflight. No registration, claim, stop, write or reusable permit.
 * Original authority and immutable evidence stay on the caller; workers can only make bounded GETs. */
public final class StripeBillingOwnedFailedRenewalRecoveryReader {
    private static final long CALL_NANOS=TimeUnit.SECONDS.toNanos(2);
    private final OwnedFailedRenewalMethodService methods;
    private final SubscriptionBillingProofStore proofs;
    private final ProviderOperationStore steps;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final DigestPort digest;
    private final Clock utc;
    private final LongSupplier nanos;

    public StripeBillingOwnedFailedRenewalRecoveryReader(OwnedFailedRenewalMethodService methods,
            SubscriptionBillingProofStore proofs,ProviderOperationStore steps,StripeCredentialResolver credentials,
            StripeClientFactory clients,DigestPort digest,Clock utc){
        this(methods,proofs,steps,credentials,clients,digest,utc,System::nanoTime);
    }
    StripeBillingOwnedFailedRenewalRecoveryReader(OwnedFailedRenewalMethodService methods,
            SubscriptionBillingProofStore proofs,ProviderOperationStore steps,StripeCredentialResolver credentials,
            StripeClientFactory clients,DigestPort digest,Clock utc,LongSupplier nanos){
        this.methods=Objects.requireNonNull(methods);this.proofs=Objects.requireNonNull(proofs);this.steps=Objects.requireNonNull(steps);
        this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);this.digest=Objects.requireNonNull(digest);
        this.utc=Objects.requireNonNull(utc);this.nanos=Objects.requireNonNull(nanos);
    }
    public record PreparedRecovery(RecoveryPreparationContext original,Snapshot snapshot){
        public PreparedRecovery {Objects.requireNonNull(original);Objects.requireNonNull(snapshot);}
        @Override public String toString(){return "PreparedRecovery[redacted]";}
    }
    public PreparedRecovery inspect(Reservation reservation,Completion completion,Recovery recovery,SubscriptionCommandContext command){
        try{
            return inspect(reservation,completion,recovery,command,new InvocationBudget(utc,nanos));
        }catch(RuntimeException failure){throw unavailable();}
    }
    /** The financial producer owns this invocation; the read cannot restart its deadline. */
    PreparedRecovery inspect(Reservation reservation,Completion completion,Recovery recovery,SubscriptionCommandContext command,InvocationBudget invocation){
        try{
            var budget=new Budget(invocation);budget.remaining(null);
            // No caller-derived proof/step/credential locator is used before actual original authentication.
            var original=methods.inspectBrowserRecoveryPreparation(reservation,completion,recovery,command);
            var expiry=original.enrollment().sessionExpiresAt();budget.remaining(expiry);
            if(!original.reservation().equals(reservation)||!original.completion().equals(completion)||!original.recovery().equals(recovery))throw unavailable();
            var intent=original.reservation().admission().firstRenewalAdmission().receipt().intent();
            if(!StripeBillingTestClockAdapter.API_VERSION.equals(intent.clock().owner().apiVersion()))throw unavailable();
            var owner=new StripeBillingSnapshotMapper.Ownership(original.account(),original.subscription(),original.binding(),
                List.of(new StripeBillingSnapshotMapper.PublishedPrice(intent.terms(),intent.priceReference(),intent.productReference())));
            var candidate=load(original,budget);
            StripeBillingSnapshotMapper.requireOwnedRecoveryPreparation(owner,original,candidate,digest,budget.at(expiry));
            budget.remaining(expiry);
            var resolved=credentials.resolve(original.account());budget.remaining(expiry);
            var client=clients.create(resolved);budget.remaining(expiry);
            var reads=new Reads(budget,expiry,()->{
                budget.remaining(expiry);
                var current=methods.inspectBrowserRecoveryPreparation(reservation,completion,recovery,command);budget.remaining(expiry);
                if(!original.equals(current)||!candidate.equals(load(current,budget)))throw unavailable();
                StripeBillingSnapshotMapper.requireOwnedRecoveryPreparation(owner,current,candidate,digest,budget.at(expiry));
                budget.remaining(expiry);
            });
            StripeBillingOwnedFailedRenewalMethodObserver.exactClock(reads.execute(()->client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),reads.options())),reservation);
            var snapshot=StripeBillingSnapshotMapper.retrieveOwnedRecoveryPreparation(client,owner,original,candidate,digest,budget.at(expiry),reads);
            StripeBillingOwnedFailedRenewalMethodObserver.exactClock(reads.execute(()->client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),reads.options())),reservation);
            reads.checkContext();budget.remaining(expiry);
            return new PreparedRecovery(original,snapshot);
        }catch(RuntimeException failure){throw unavailable();}
    }
    private StripeBillingSnapshotMapper.OwnedMethodCandidate load(RecoveryPreparationContext original,Budget budget){
        var expiry=original.enrollment().sessionExpiresAt();var c=original.completion();budget.remaining(expiry);
        var proof=proofs.setup(original.account().id(),c.setupOperationId()).orElseThrow(StripeBillingOwnedFailedRenewalRecoveryReader::unavailable);budget.remaining(expiry);
        var setup=steps.findById(c.setupStepId()).orElseThrow(StripeBillingOwnedFailedRenewalRecoveryReader::unavailable);budget.remaining(expiry);
        var method=steps.findById(c.methodStepId()).orElseThrow(StripeBillingOwnedFailedRenewalRecoveryReader::unavailable);budget.remaining(expiry);
        return new StripeBillingSnapshotMapper.OwnedMethodCandidate(original.setupOperation(),proof,setup,method);
    }
    /** One immutable invocation budget, including authentication, stores and credential/client work.
     * Synchronization serializes caller/worker sampling so raw UTC and injected rollback cannot hide. */
    private final class Budget {
        final InvocationBudget invocation;
        private Instant lastUtc;
        Budget(InvocationBudget invocation){this.invocation=Objects.requireNonNull(invocation);lastUtc=invocation.enteredAt();}
        synchronized long remaining(Instant expiry){
            long left=invocation.remainingNanos(null);var now=utc.instant();
            if(Thread.currentThread().isInterrupted()||now.isBefore(lastUtc)||now.isBefore(invocation.enteredAt()))throw unavailable();
            left=Math.min(left,Duration.between(now,invocation.deadlineAt()).toNanos());
            if(expiry!=null)left=Math.min(left,Duration.between(now,expiry).toNanos());
            if(left<=0)throw unavailable();lastUtc=now;return left;
        }
        synchronized Instant at(Instant expiry){remaining(expiry);return lastUtc;}
    }
    private final class Reads implements StripeBillingSnapshotMapper.ReadExecutor {
        final Budget budget;final Instant expiry;final Runnable inspect;
        int count;RequestOptions options;
        Reads(Budget budget,Instant expiry,Runnable inspect){this.budget=budget;this.expiry=expiry;this.inspect=inspect;}
        public RequestOptions options(){return options;}
        void checkContext(){budget.remaining(expiry);inspect.run();budget.remaining(expiry);}
        public <T>T execute(StripeBillingSnapshotMapper.Call<T> call){
            if(++count>100)throw unavailable();checkContext();
            long actual=System.nanoTime(),injected=nanos.getAsLong();var at=budget.at(expiry);
            long allowance=Math.min(CALL_NANOS,budget.remaining(expiry));
            var deadline=at.plusNanos(allowance);
            int millis=(int)TimeUnit.NANOSECONDS.toMillis(allowance);if(millis<=0)throw unavailable();
            var builder=RequestOptions.builder().setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
            RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,StripeBillingTestClockAdapter.API_VERSION);options=builder.build();
            LongSupplier remaining=()->{
                long left=budget.remaining(expiry);long actualElapsed=System.nanoTime()-actual,injectedElapsed=nanos.getAsLong()-injected;
                if(actualElapsed<0||injectedElapsed<0)throw unavailable();
                left=Math.min(left,Math.min(allowance-actualElapsed,Math.min(allowance-injectedElapsed,Duration.between(budget.at(expiry),deadline).toNanos())));
                if(left<=0)throw unavailable();return left;
            };
            var pending=new FutureTask<T>(()->{remaining.getAsLong();return call.run();});
            Thread.ofVirtual().name("owned-recovery-preflight-read").start(pending);
            try{
                var result=pending.get(remaining.getAsLong(),TimeUnit.NANOSECONDS);remaining.getAsLong();
                checkContext();remaining.getAsLong();return result;
            }catch(InterruptedException failure){Thread.currentThread().interrupt();throw unavailable();}
            catch(ExecutionException|TimeoutException failure){throw unavailable();}
            finally{pending.cancel(true);}
        }
    }
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("OWNED_RECOVERY_PREFLIGHT_UNAVAILABLE",409,"Owned recovery preflight unavailable");}
    @Override public String toString(){return "StripeBillingOwnedFailedRenewalRecoveryReader[redacted]";}
}
