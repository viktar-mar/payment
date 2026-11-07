package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Mechanical bounds and terminal cleanup shared only by the two original owned method writes. */
final class StripeBillingOwnedMethodWrite {
    private static final long INVOCATION=TimeUnit.SECONDS.toNanos(30),CALL=TimeUnit.SECONDS.toNanos(2);
    private StripeBillingOwnedMethodWrite() {}
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
        try{
            var current=steps.findById(claim.id());
            if(current.isPresent()&&claim.equals(current.get()))steps.unknown(claim.id(),claim.leaseOwner(),claim.version());
        }catch(RuntimeException ignored){/* Uncertain evidence never authorizes a replacement. */}
        try{methods.stop(reservation,OwnedFailedRenewal.Reason.UNCERTAIN);}
        catch(RuntimeException ignored){/* Preserve the consumed claim even if stop persistence fails. */}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException();}
}
