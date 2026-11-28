package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Origin;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.SuccessorOwner;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import java.time.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Mechanical bounds and terminal cleanup shared only by the two original owned method writes. */
final class StripeBillingOwnedMethodWrite {
    private static final long INVOCATION=TimeUnit.SECONDS.toNanos(30),CALL=TimeUnit.SECONDS.toNanos(2);
    private final ProviderOperationStore steps;
    private final Reservation reservation;
    private final ProviderOperation.Kind kind;
    private final Origin origin;
    private final SuccessorOwner owner;

    StripeBillingOwnedMethodWrite(ProviderOperationStore steps,Reservation reservation,ProviderOperation.Kind kind){
        this.steps=steps;this.reservation=reservation;this.kind=kind;
        origin=switch(kind){
            case SETUP_INTENT_CREATE->Origin.SETUP_WRITE;
            case SUBSCRIPTION_UPDATE->Origin.METHOD_WRITE;
            default->throw unavailable();
        };
        owner=reservation.successor()==null?null:SuccessorOwner.create();
    }
    ProviderOperation reserve(PaymentAccount account){
        return owner==null?steps.reserveOwnedFailedRenewalMethodStep(account,reservation,kind)
            :steps.reserveOwnedFailedRenewalMethodStep(account,reservation,kind,owner);
    }
    String claimant(UUID step){return owner==null?UUID.randomUUID().toString():owner.commitment(origin,step);}
    Optional<ProviderOperation> claim(UUID step,String claimant,Instant now,Duration lease){
        return owner==null?steps.claim(step,claimant,now,lease):steps.claim(step,claimant,now,lease,owner);
    }
    ProviderOperation inspect(PaymentAccount account,ProviderOperation claim,Instant now){
        return owner==null?steps.requireOwnedFailedRenewalMethodDispatch(account,reservation,claim,now)
            :steps.requireOwnedFailedRenewalMethodDispatch(account,reservation,claim,now,owner);
    }
    ProviderOperation acknowledge(ProviderOperation claim,String reference){
        return owner==null?steps.acknowledge(claim.id(),claim.leaseOwner(),claim.version(),reference)
            :steps.acknowledge(claim.id(),claim.leaseOwner(),claim.version(),reference,owner);
    }
    void terminate(OwnedFailedRenewalMethodService methods,ProviderOperation original){
        terminate(methods,steps,reservation,original,owner);
    }
    interface Call<T> { T run(RequestOptions options)throws StripeException; }

    static final class Budget {
        private final Clock utc;
        private final LongSupplier nanos;
        private final long actualStart,injectedStart;
        private final Instant entered,deadline;
        Budget(Clock utc,LongSupplier nanos){
            this.utc=Objects.requireNonNull(utc);this.nanos=Objects.requireNonNull(nanos);
            actualStart=System.nanoTime();injectedStart=nanos.getAsLong();entered=utc.instant();deadline=entered.plusSeconds(30);
        }
        long remaining(ProviderOperation claim){
            if(Thread.currentThread().isInterrupted())throw unavailable();
            long actual=System.nanoTime()-actualStart,injected=nanos.getAsLong()-injectedStart;
            Instant now=utc.instant();
            if(actual<0||injected<0||now.isBefore(entered))throw unavailable();
            long left=Math.min(Math.min(INVOCATION-actual,INVOCATION-injected),Duration.between(now,deadline).toNanos());
            if(claim!=null){
                if(now.isBefore(claim.firstDispatchedAt())||!now.isBefore(claim.leaseExpiresAt())||!now.isBefore(claim.retryBefore()))throw unavailable();
                left=Math.min(left,Duration.between(now,claim.leaseExpiresAt()).toNanos());
            }
            if(left<=0)throw unavailable();return left;
        }
        <T>T call(ProviderOperation claim,Instant originalSessionExpiry,String dispatchKey,Call<T> work){
            long started=System.nanoTime();Instant callDeadline=utc.instant().plusSeconds(2);
            int millis=(int)TimeUnit.NANOSECONDS.toMillis(callRemaining(claim,started,callDeadline));
            if(millis<=0)throw unavailable();
            var optionsBuilder=RequestOptions.builder().setIdempotencyKey(dispatchKey).setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
            RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(optionsBuilder,StripeBillingTestClockAdapter.API_VERSION);
            var options=optionsBuilder.build();
            var pending=new FutureTask<T>(()->{
                // Scheduling cannot extend known call/invocation/lease or original session expiry.
                callRemaining(claim,started,callDeadline);
                if(!utc.instant().isBefore(originalSessionExpiry))throw unavailable();
                return work.run(options);
            });
            callRemaining(claim,started,callDeadline);
            Thread.ofVirtual().name("owned-method-write").start(pending);
            try{
                T remote=pending.get(callRemaining(claim,started,callDeadline),TimeUnit.NANOSECONDS);
                callRemaining(claim,started,callDeadline);
                return remote;
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
            catch(ExecutionException|TimeoutException uncertain){throw unavailable();}
            finally{pending.cancel(true);}
        }
        private long callRemaining(ProviderOperation claim,long started,Instant callDeadline){
            long elapsed=System.nanoTime()-started;
            long left=Math.min(remaining(claim),Math.min(CALL-elapsed,Duration.between(utc.instant(),callDeadline).toNanos()));
            if(Thread.currentThread().isInterrupted()||elapsed<0||left<=0)throw unavailable();return left;
        }
    }
    static void terminate(OwnedFailedRenewalMethodService methods,ProviderOperationStore steps,Reservation reservation,ProviderOperation claim){
        if(reservation.successor()!=null)return;
        try{
            var current=steps.findById(claim.id());
            if(current.isPresent()&&claim.equals(current.get()))steps.unknown(claim.id(),claim.leaseOwner(),claim.version());
        }catch(RuntimeException ignored){/* Uncertain evidence never authorizes a replacement. */}
        try{methods.stop(reservation,OwnedFailedRenewal.Reason.UNCERTAIN);}
        catch(RuntimeException ignored){/* Preserve the consumed claim even if stop persistence fails. */}
    }
    static void terminate(OwnedFailedRenewalMethodService methods,ProviderOperationStore steps,Reservation reservation,
            ProviderOperation original,SuccessorOwner owner){
        if(reservation.successor()==null){terminate(methods,steps,reservation,original);return;}
        if(owner!=null)methods.terminateSuccessorWrite(reservation,original,owner);
    }
    private static IllegalStateException unavailable(){return new IllegalStateException();}
}
