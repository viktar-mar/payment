package dev.genericpayments.platform.provider.application.model;

import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Evidence from the single original-intent write. It never grants settlement or callback authority. */
public final class OwnedRecoveryConfirm {
    private OwnedRecoveryConfirm(){}
    public record ConfirmReply(String paymentIntent,String customer,String method,String currency,long amount,long received,long capturable,String charge,Instant observedAt){
        public ConfirmReply{
            if(!reference(paymentIntent,"pi_",252)||!reference(customer,"cus_",251)||!reference(method,"pm_",252)
                    ||currency==null||!currency.matches("[A-Z]{3}")||amount<=0||received!=amount||capturable!=0
                    ||!reference(charge,"ch_",252)||observedAt==null)throw new IllegalArgumentException("Invalid owned recovery confirmation evidence");
        }
        @Override public String toString(){return "ConfirmReply[redacted]";}
    }
    public record ConfirmationAck(ProviderOperation operation,ConfirmReply reply,Instant acknowledgedAt){
        public ConfirmationAck{
            if(operation==null||reply==null||acknowledgedAt==null||operation.kind()!=ProviderOperation.Kind.OWNED_RECOVERY_INTENT_CONFIRM
                    ||operation.status()!=ProviderOperation.Status.ACKNOWLEDGED||operation.version()!=2
                    ||!reply.paymentIntent().equals(operation.providerReference())||operation.leaseOwner()!=null||operation.leaseExpiresAt()!=null
                    ||operation.firstDispatchedAt()==null||reply.observedAt().isBefore(operation.firstDispatchedAt())||acknowledgedAt.isBefore(reply.observedAt()))throw unavailable();
        }
        @Override public String toString(){return "ConfirmationAck[redacted]";}
    }
    /** One immutable invocation, captured before authentication. All three clocks may only consume time. */
    public static final class InvocationBudget {
        private static final long LIMIT=30_000_000_000L;
        private final Clock clock;private final LongSupplier monotonic;private final long start,actualStart;
        private final UUID invocation;private final Instant enteredAt,deadlineAt;
        private long lastMonotonic,lastActual;private Instant lastUtc;
        public InvocationBudget(Clock clock,LongSupplier monotonic){
            this.clock=Objects.requireNonNull(clock);this.monotonic=Objects.requireNonNull(monotonic);
            actualStart=System.nanoTime();start=monotonic.getAsLong();enteredAt=clock.instant();deadlineAt=enteredAt.plusSeconds(30);invocation=UUID.randomUUID();
            lastMonotonic=start;lastActual=actualStart;lastUtc=enteredAt;
        }
        public UUID invocation(){return invocation;}public Instant enteredAt(){return enteredAt;}public Instant deadlineAt(){return deadlineAt;}
        /** Null means the initial preclaim checkpoint; a claim additionally bounds the live lease. */
        public synchronized long remainingNanos(ProviderOperation claim){
            if(Thread.currentThread().isInterrupted())throw unavailable();
            long injected=monotonic.getAsLong(),actual=System.nanoTime();var now=clock.instant();
            if(injected<lastMonotonic||actual<lastActual||now.isBefore(lastUtc))throw unavailable();
            lastMonotonic=injected;lastActual=actual;lastUtc=now;long elapsed=injected-start,actualElapsed=actual-actualStart;
            if(elapsed<0||actualElapsed<0||elapsed>=LIMIT||actualElapsed>=LIMIT||now.isBefore(enteredAt)||!now.isBefore(deadlineAt))throw unavailable();
            long remaining=Math.min(Math.min(LIMIT-elapsed,LIMIT-actualElapsed),Duration.between(now,deadlineAt).toNanos());
            if(claim!=null){
                if(claim.firstDispatchedAt()==null||claim.leaseExpiresAt()==null||now.isBefore(claim.firstDispatchedAt())||!now.isBefore(claim.leaseExpiresAt()))throw unavailable();
                remaining=Math.min(remaining,Duration.between(now,claim.leaseExpiresAt()).toNanos());
            }
            if(remaining<=0)throw unavailable();return remaining;
        }
        @Override public String toString(){return "InvocationBudget[redacted]";}
    }
    private static boolean reference(String value,String prefix,int suffix){return value!=null&&value.matches(prefix+"[A-Za-z0-9]{1,"+suffix+"}");}
    private static IllegalStateException unavailable(){return new IllegalStateException("OWNED_RECOVERY_CONFIRM_UNAVAILABLE");}
}
