package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Explicit, unregistered observers. Descriptive SDK evidence gains authority only in the service publisher. */
public final class StripeBillingOwnedFailedRenewalMethodObserver {
    private static final long TOTAL_NANOS=Duration.ofSeconds(30).toNanos();
    private static final long CALL_NANOS=Duration.ofSeconds(2).toNanos();
    private final OwnedFailedRenewalMethodService service;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final DigestPort digest;
    private final Clock utc;
    private final LongSupplier nanos;

    public StripeBillingOwnedFailedRenewalMethodObserver(OwnedFailedRenewalMethodService service,
            StripeCredentialResolver credentials,StripeClientFactory clients,DigestPort digest,Clock utc) {
        this(service,credentials,clients,digest,utc,System::nanoTime);
    }
    StripeBillingOwnedFailedRenewalMethodObserver(OwnedFailedRenewalMethodService service,
            StripeCredentialResolver credentials,StripeClientFactory clients,DigestPort digest,Clock utc,LongSupplier nanos) {
        this.service=Objects.requireNonNull(service);this.credentials=Objects.requireNonNull(credentials);
        this.clients=Objects.requireNonNull(clients);this.digest=Objects.requireNonNull(digest);
        this.utc=Objects.requireNonNull(utc);this.nanos=Objects.requireNonNull(nanos);
    }

    public Completion observeMethod(Reservation reservation) {
        Objects.requireNonNull(reservation);
        long actualStart=System.nanoTime(),start=nanos.getAsLong();var entry=now();var invocation=UUID.randomUUID();
        ReadClaim claim;
        // Begin and publication own their rollback/stop. Never stop those failures a second time.
        try {claim=service.beginMethodRead(reservation,invocation);}
        catch(RuntimeException failure) {throw serviceFailure(failure);}
        MethodReadContext context;MethodEvidence evidence;Reads reads=null;
        try {
            requireClaim(reservation,claim,Phase.METHOD,invocation,entry,reservation.setupOperationId(),reservation.initialOperationVersion()+1,
                reservation.failure().observationSequence());
            var budget=new Budget(start,actualStart,claim.deadlineAt());budget.remaining();
            context=inspectMethod(reservation,claim);budget.remaining();
            var expected=context;
            reads=new Reads(budget,()->{if(!expected.equals(inspectMethod(reservation,claim)))throw new Drift();});
            var client=clients.create(credentials.resolve(context.account()));budget.remaining();
            var reader=reads;var admission=reservation.admission();var intent=admission.firstRenewalAdmission().receipt().intent();
            exactClock(reader.execute(()->client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),reader.options())),reservation);
            var owner=new StripeBillingSnapshotMapper.Ownership(context.account(),context.subscription(),context.binding(),
                List.of(new StripeBillingSnapshotMapper.PublishedPrice(intent.terms(),intent.priceReference(),intent.productReference())));
            var candidate=new StripeBillingSnapshotMapper.OwnedMethodCandidate(context.operation(),context.setupProof(),context.setupStep(),context.methodStep());
            var mapped=StripeBillingSnapshotMapper.retrieveOwnedMethodReady(client,owner,admission,reservation.failure(),candidate,digest,now(),reader);
            exactClock(reader.execute(()->client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),reader.options())),reservation);
            reader.checkContext();budget.remaining();var accepted=now();claim.requireAccepted(mapped.snapshot().observedAt(),accepted);
            evidence=new MethodEvidence(mapped.snapshot(),mapped.setupIntent(),mapped.method(),accepted);
        } catch(RuntimeException failure) {throw stopped(reservation,failure,reads);}
        try {return service.publishMethod(reservation,claim,evidence,context);}
        catch(RuntimeException failure) {throw serviceFailure(failure);}
    }

    public Publication observeRecovery(Reservation reservation,Recovery recovery) {
        Objects.requireNonNull(reservation);Objects.requireNonNull(recovery);
        long actualStart=System.nanoTime(),start=nanos.getAsLong();var entry=now();var invocation=UUID.randomUUID();
        ReadClaim claim;
        try {claim=service.beginRecoveredRead(reservation,recovery,invocation);}
        catch(RuntimeException failure) {throw serviceFailure(failure);}
        RecoveredReadContext context;OwnedFailedRenewal.RecoveredEvidence evidence;Reads reads=null;
        try {
            if(!reservation.id().equals(recovery.reservationId())||recovery.observationSequence()!=reservation.failure().observationSequence()+1)throw new Drift();
            requireClaim(reservation,claim,Phase.RECOVERED,invocation,entry,recovery.association().operationId(),recovery.association().operationVersion(),recovery.observationSequence());
            var budget=new Budget(start,actualStart,claim.deadlineAt());budget.remaining();
            context=inspectRecovery(reservation,claim);budget.remaining();
            if(!recovery.equals(context.recovery()))throw new Drift();
            var expected=context;
            reads=new Reads(budget,()->{if(!expected.equals(inspectRecovery(reservation,claim)))throw new Drift();});
            var client=clients.create(credentials.resolve(context.account()));budget.remaining();
            var reader=reads;var admission=reservation.admission();var intent=admission.firstRenewalAdmission().receipt().intent();
            exactClock(reader.execute(()->client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),reader.options())),reservation);
            var owner=new StripeBillingSnapshotMapper.Ownership(context.account(),context.subscription(),context.binding(),
                List.of(new StripeBillingSnapshotMapper.PublishedPrice(intent.terms(),intent.priceReference(),intent.productReference())));
            var mapped=StripeBillingSnapshotMapper.retrieveOwnedRecoveredRenewal(client,owner,admission,reservation.failure(),context.recovery().association(),context.operation(),now(),reader);
            exactClock(reader.execute(()->client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),reader.options())),reservation);
            reader.checkContext();budget.remaining();var accepted=now();claim.requireAccepted(mapped.snapshot().observedAt(),accepted);
            evidence=new OwnedFailedRenewal.RecoveredEvidence(mapped.snapshot(),accepted);
        } catch(RuntimeException failure) {throw stopped(reservation,failure,reads);}
        // Return the committed receipt even when commit itself consumed the remaining observation budget.
        try {return service.publishRecovery(reservation,claim,evidence,context);}
        catch(RuntimeException failure) {throw serviceFailure(failure);}
    }

    private void requireClaim(Reservation r,ReadClaim q,Phase phase,UUID invocation,Instant entry,UUID operation,long version,long sequence) {
        if(q==null||q.phase()!=phase||!r.id().equals(q.reservationId())||!invocation.equals(q.invocation())
                ||!q.deadlineAt().equals(q.startedAt().plusSeconds(30))||q.startedAt().isBefore(entry)||q.startedAt().isAfter(now())
                ||!r.billingSample().equals(q.billingSample())||q.fence().revision()!=r.failure().revision()
                ||!operation.equals(q.fence().operationId())||!Objects.equals(version,q.fence().operationVersion())
                ||!Objects.equals(sequence,q.fence().observationSequence())||q.fence().lease()!=null||q.fence().authority()!=null)throw new Drift();
    }
    private MethodReadContext inspectMethod(Reservation r,ReadClaim q) {
        try {var c=service.inspectMethodRead(r,q);if(c==null||!r.billingSample().equals(c.billingSample()))throw new Drift();return c;}
        catch(RuntimeException failure) {throw new Drift();}
    }
    private RecoveredReadContext inspectRecovery(Reservation r,ReadClaim q) {
        try {var c=service.inspectRecoveredRead(r,q);if(c==null||!r.billingSample().equals(c.billingSample()))throw new Drift();return c;}
        catch(RuntimeException failure) {throw new Drift();}
    }
    private PaymentApplicationException stopped(Reservation r,RuntimeException failure,Reads reads) {
        var result=safe();var reason=failure instanceof Drift||reads!=null&&reads.drift?OwnedFailedRenewal.Reason.AUTHORITY_DRIFT:OwnedFailedRenewal.Reason.UNCERTAIN;
        try {service.stop(r,reason);}catch(RuntimeException ignored) {result.addSuppressed(new IllegalStateException("Owned method stop unavailable"));}
        return result;
    }
    private Instant now(){return utc.instant().truncatedTo(ChronoUnit.MICROS);}
    static void exactClock(TestClock remote,Reservation r) {
        var expected=r.admission().ready().observation();
        if(remote==null||!expected.reference().equals(remote.getId())||!"test_helpers.test_clock".equals(remote.getObject())
                ||!Boolean.FALSE.equals(remote.getLivemode())||Boolean.TRUE.equals(remote.getDeleted())
                ||!expected.name().equals(remote.getName())||!Objects.equals(expected.frozenTime(),remote.getFrozenTime())
                ||!Objects.equals(expected.created(),remote.getCreated())||!Objects.equals(expected.deletesAfter(),remote.getDeletesAfter())
                ||!"ready".equals(remote.getStatus())||remote.getStatusDetails()!=null&&remote.getStatusDetails().getAdvancing()!=null
                &&!Objects.equals(expected.frozenTime(),remote.getStatusDetails().getAdvancing().getTargetFrozenTime()))throw safe();
    }
    private final class Budget {
        private final long start,actualStart;private final Instant deadline;
        Budget(long start,long actualStart,Instant deadline){this.start=start;this.actualStart=actualStart;this.deadline=deadline;}
        long remaining(){long actual=System.nanoTime()-actualStart,injected=nanos.getAsLong()-start;
            long left=Math.min(TOTAL_NANOS-actual,Math.min(TOTAL_NANOS-injected,Duration.between(utc.instant(),deadline).toNanos()));
            if(Thread.currentThread().isInterrupted()||actual<0||injected<0||left<=0)throw safe();return left;}
    }
    private final class Reads implements StripeBillingSnapshotMapper.ReadExecutor {
        private final Budget budget;private final Runnable inspect;private int count;private boolean drift;private RequestOptions options;
        Reads(Budget budget,Runnable inspect){this.budget=budget;this.inspect=inspect;}
        public RequestOptions options(){return options;}
        void checkContext(){try {inspect.run();}catch(Drift failure){drift=true;throw failure;}}
        public <T>T execute(StripeBillingSnapshotMapper.Call<T> call) {
            if(++count>100)throw safe();checkContext();long allowance=Math.min(CALL_NANOS,budget.remaining());
            int millis=(int)TimeUnit.NANOSECONDS.toMillis(allowance);if(millis<=0)throw safe();
            var builder=RequestOptions.builder().setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
            RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,StripeBillingTestClockAdapter.API_VERSION);options=builder.build();
            long actual=System.nanoTime(),injected=nanos.getAsLong();
            var pending=new FutureTask<T>(()->{budget.remaining();
                if(System.nanoTime()-actual>=allowance||nanos.getAsLong()-injected>=allowance)throw safe();return call.run();});
            Thread.ofVirtual().name("owned-failed-renewal-method-read").start(pending);
            try {long remaining=Math.min(allowance-(System.nanoTime()-actual),budget.remaining());if(remaining<=0)throw safe();
                var result=pending.get(remaining,TimeUnit.NANOSECONDS);
                if(System.nanoTime()-actual>=allowance||nanos.getAsLong()-injected>=allowance)throw safe();
                budget.remaining();checkContext();budget.remaining();return result;
            }catch(InterruptedException failure){Thread.currentThread().interrupt();throw safe();}
            catch(ExecutionException failure){if(failure.getCause() instanceof Error error)throw error;throw safe();}
            catch(TimeoutException failure){throw safe();}finally {pending.cancel(true);}
        }
    }
    private static final class Drift extends RuntimeException {}
    private static PaymentApplicationException safe(){return new PaymentApplicationException("OWNED_FAILED_RENEWAL_METHOD_UNAVAILABLE",409,"Owned method observation unavailable");}
    private static PaymentApplicationException serviceFailure(RuntimeException failure){var result=safe();if(failure.getSuppressed().length>0)result.addSuppressed(new IllegalStateException("Owned method stop unavailable"));return result;}
}
