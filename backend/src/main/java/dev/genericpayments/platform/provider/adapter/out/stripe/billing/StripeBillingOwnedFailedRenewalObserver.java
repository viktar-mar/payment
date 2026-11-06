package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalService;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalService.FailedReadContext;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Explicit, unregistered one-shot observer. Database authority stays on the caller thread. */
public final class StripeBillingOwnedFailedRenewalObserver {
    private static final long TOTAL_NANOS=Duration.ofSeconds(30).toNanos();
    private static final long CALL_NANOS=Duration.ofSeconds(2).toNanos();
    private final OwnedFailedRenewalService service;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final Clock utc;
    private final LongSupplier nanos;

    public StripeBillingOwnedFailedRenewalObserver(OwnedFailedRenewalService service,StripeCredentialResolver credentials,
            StripeClientFactory clients,Clock utc) {
        this(service,credentials,clients,utc,System::nanoTime);
    }
    StripeBillingOwnedFailedRenewalObserver(OwnedFailedRenewalService service,StripeCredentialResolver credentials,
            StripeClientFactory clients,Clock utc,LongSupplier nanos) {
        this.service=Objects.requireNonNull(service);
        this.credentials=Objects.requireNonNull(credentials);
        this.clients=Objects.requireNonNull(clients);
        this.utc=Objects.requireNonNull(utc);
        this.nanos=Objects.requireNonNull(nanos);
    }

    public Failure observe(Admission admission) {
        long actualStart=System.nanoTime();
        var entry=now();
        long start=nanos.getAsLong();
        var invocation=UUID.randomUUID();
        ReadClaim claim;
        // The service owns begin/publication rollback and terminal stop. Never stop those twice.
        try {claim=service.beginFailedRead(admission,invocation);}
        catch(RuntimeException failure) {throw serviceFailure(failure);}

        FailedReadContext context;
        FailedEvidence evidence;
        Reads reader=null;
        try {
            if(claim==null||claim.phase()!=Phase.FAILED||!claim.admissionId().equals(admission.id())
                    ||!claim.invocation().equals(invocation)||!claim.deadlineAt().equals(claim.startedAt().plusSeconds(30))
                    ||claim.startedAt().isBefore(entry)||claim.startedAt().isAfter(now()))throw new Drift();
            var budget=new Budget(start,actualStart,claim.deadlineAt());
            budget.remaining();
            context=inspect(admission,claim);
            budget.remaining();
            var client=clients.create(credentials.resolve(context.account()));
            budget.remaining();
            reader=new Reads(admission,claim,context,budget);
            var reads=reader;
            var intent=admission.firstRenewalAdmission().receipt().intent();
            exactClock(reads.execute(()->client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),reads.options())),admission);
            var ownership=new StripeBillingSnapshotMapper.Ownership(context.account(),context.subscription(),context.binding(),
                List.of(new StripeBillingSnapshotMapper.PublishedPrice(intent.terms(),intent.priceReference(),intent.productReference())));
            var mapped=StripeBillingSnapshotMapper.retrieveOwnedFailedRenewal(client,ownership,admission,now(),reads);
            exactClock(reads.execute(()->client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),reads.options())),admission);
            reads.checkContext();
            budget.remaining();
            var accepted=now();
            claim.requireAccepted(mapped.snapshot().observedAt(),accepted);
            evidence=new FailedEvidence(mapped.snapshot(),mapped.allocation(),mapped.paymentIntent(),accepted);
        } catch(RuntimeException failure) {
            var result=safe();
            var reason=failure instanceof Drift||reader!=null&&reader.drift?Reason.AUTHORITY_DRIFT:Reason.UNCERTAIN;
            try {service.stop(admission,reason);}
            catch(RuntimeException ignored) {result.addSuppressed(new IllegalStateException("Failed renewal stop unavailable"));}
            throw result;
        }
        // Return the actual persisted receipt. A delayed commit must never be retroactively rejected.
        try {return service.publishFailure(admission,claim,evidence,context);}
        catch(RuntimeException failure) {throw serviceFailure(failure);}
    }

    private FailedReadContext inspect(Admission admission,ReadClaim claim) {
        try {return service.inspectFailedRead(admission,claim);}
        catch(RuntimeException failure) {throw new Drift();}
    }
    private Instant now(){return utc.instant().truncatedTo(ChronoUnit.MICROS);}
    private static void exactClock(TestClock remote,Admission admission) {
        var expected=admission.ready().observation();
        if(remote==null||!expected.reference().equals(remote.getId())||!"test_helpers.test_clock".equals(remote.getObject())
                ||!Boolean.FALSE.equals(remote.getLivemode())||Boolean.TRUE.equals(remote.getDeleted())
                ||!expected.name().equals(remote.getName())||!Objects.equals(expected.frozenTime(),remote.getFrozenTime())
                ||!Objects.equals(expected.created(),remote.getCreated())||!Objects.equals(expected.deletesAfter(),remote.getDeletesAfter())
                ||!"ready".equals(remote.getStatus())||remote.getStatusDetails()!=null&&remote.getStatusDetails().getAdvancing()!=null
                &&!Objects.equals(expected.frozenTime(),remote.getStatusDetails().getAdvancing().getTargetFrozenTime()))throw safe();
    }

    private final class Budget {
        private final long start,actualStart;
        private final Instant deadline;
        Budget(long start,long actualStart,Instant deadline){this.start=start;this.actualStart=actualStart;this.deadline=deadline;}
        long remaining() {
            long actualElapsed=System.nanoTime()-actualStart,injectedElapsed=nanos.getAsLong()-start;
            long left=Math.min(TOTAL_NANOS-actualElapsed,Math.min(TOTAL_NANOS-injectedElapsed,Duration.between(utc.instant(),deadline).toNanos()));
            if(Thread.currentThread().isInterrupted()||actualElapsed<0||injectedElapsed<0||left<=0)throw safe();
            return left;
        }
    }

    private final class Reads implements StripeBillingSnapshotMapper.ReadExecutor {
        private final Admission admission;
        private final ReadClaim claim;
        private final FailedReadContext context;
        private final Budget budget;
        private int count;
        private boolean drift;
        private RequestOptions options;
        Reads(Admission admission,ReadClaim claim,FailedReadContext context,Budget budget) {
            this.admission=admission;this.claim=claim;this.context=context;this.budget=budget;
        }
        public RequestOptions options(){return options;}
        private void checkContext() {
            try {if(!context.equals(inspect(admission,claim)))throw new Drift();}
            catch(Drift failure){drift=true;throw failure;}
        }
        public <T>T execute(StripeBillingSnapshotMapper.Call<T> call) {
            if(++count>100)throw safe();
            checkContext();
            long allowance=Math.min(CALL_NANOS,budget.remaining());
            int millis=(int)TimeUnit.NANOSECONDS.toMillis(allowance);
            if(millis<=0)throw safe();
            var builder=RequestOptions.builder().setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
            RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,StripeBillingTestClockAdapter.API_VERSION);
            options=builder.build();
            long actual=System.nanoTime(),injected=nanos.getAsLong();
            var pending=new FutureTask<T>(()->{
                // Scheduling delay consumes both allowances before IO starts; no database work enters this worker.
                budget.remaining();
                if(System.nanoTime()-actual>=allowance||nanos.getAsLong()-injected>=allowance)throw safe();
                return call.run();
            });
            Thread.ofVirtual().name("owned-failed-renewal-read").start(pending);
            try {
                long remaining=Math.min(allowance-(System.nanoTime()-actual),budget.remaining());
                if(remaining<=0)throw safe();
                var result=pending.get(remaining,TimeUnit.NANOSECONDS);
                if(System.nanoTime()-actual>=allowance||nanos.getAsLong()-injected>=allowance)throw safe();
                budget.remaining();
                checkContext();
                budget.remaining();
                return result;
            } catch(InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw safe();
            } catch(ExecutionException failure) {
                // Programming/test transport errors must not become an apparently valid negative observation.
                if(failure.getCause() instanceof Error error)throw error;
                throw safe();
            } catch(TimeoutException failure) {throw safe();}
            finally {pending.cancel(true);}
        }
    }
    private static final class Drift extends RuntimeException {}
    private static PaymentApplicationException safe() {
        return new PaymentApplicationException("FAILED_RENEWAL_UNAVAILABLE",409,"Failed renewal observation unavailable");
    }
    private static PaymentApplicationException serviceFailure(RuntimeException failure) {
        var result=safe();
        // guarded() attaches the terminal-stop failure. Preserve that fact without its diagnostic payload.
        if(failure.getSuppressed().length>0)result.addSuppressed(new IllegalStateException("Failed renewal stop unavailable"));
        return result;
    }
}
