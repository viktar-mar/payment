package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import com.stripe.param.testhelpers.TestClockAdvanceParams;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit internal consumer; never registered as an ordinary Billing adapter. */
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NEVER)
public class StripeBillingFirstRenewalAdvanceAdapter {
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final LoadPaymentAccountPort accounts;
    private final ProviderOperationStore operations;
    private final OwnedFirstRenewalStore renewals;
    private final Clock utc;
    private final LongSupplier nanos;private final Consumer<Duration> wait;private final Consumer<String> stage;
    public StripeBillingFirstRenewalAdvanceAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            LoadPaymentAccountPort accounts,ProviderOperationStore operations,OwnedFirstRenewalStore renewals,Clock utc) {
        this(credentials,clients,accounts,operations,renewals,utc,ignored->{});
    }
    /** The callback receives only fixed source-owned checkpoints, never provider content. */
    public StripeBillingFirstRenewalAdvanceAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            LoadPaymentAccountPort accounts,ProviderOperationStore operations,OwnedFirstRenewalStore renewals,Clock utc,Consumer<String> stage) {
        this(credentials,clients,accounts,operations,renewals,utc,System::nanoTime,stage);
    }
    StripeBillingFirstRenewalAdvanceAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            LoadPaymentAccountPort accounts,ProviderOperationStore operations,OwnedFirstRenewalStore renewals,Clock utc,LongSupplier nanos,Consumer<String> stage) {
        this(credentials,clients,accounts,operations,renewals,utc,nanos,duration->{
            try{Thread.sleep(duration);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        },stage);
    }
    StripeBillingFirstRenewalAdvanceAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            LoadPaymentAccountPort accounts,ProviderOperationStore operations,OwnedFirstRenewalStore renewals,Clock utc,
            LongSupplier nanos,Consumer<Duration> wait,Consumer<String> stage) {
        this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);
        this.accounts=Objects.requireNonNull(accounts);this.operations=Objects.requireNonNull(operations);
        this.renewals=Objects.requireNonNull(renewals);this.utc=Objects.requireNonNull(utc);
        this.nanos=Objects.requireNonNull(nanos);this.wait=Objects.requireNonNull(wait);this.stage=Objects.requireNonNull(stage);
    }
    public LaterReady advance(Admission admission,Step step) {
        stage.accept("CLOCK_ADVANCE_AUTHORITY");
        if(TransactionSynchronizationManager.isActualTransactionActive()||admission==null||step==null)throw unavailable();
        long began=nanos.getAsLong();
        Boundary boundary;Advance advance;PaymentAccount account;ProviderOperation claim;
        String claimant=UUID.randomUUID().toString();
        try {
            current(admission);
            var completed=renewals.ready(admission,step);
            if(completed.isPresent())return completed.get();
            stage.accept("CLOCK_ADVANCE_PREPARE");
            boundary=step==Step.FINALIZATION?renewals.boundary(admission):null;
            advance=renewals.prepare(admission,step,boundary,utc.instant());
            dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.requireAdvanceWindow(admission,step,advance.target());
            var owner=admission.receipt().intent().clock().owner();
            stage.accept("CLOCK_ADVANCE_ACCOUNT");
            account=accounts.findById(owner.account()).orElseThrow(StripeBillingFirstRenewalAdvanceAdapter::unavailable);
            if(!owner.externalAccount().equals(account.externalAccountId())||!owner.credentialsRef().equals(account.credentialsRef())
                ||account.provider()!=PaymentProviderType.STRIPE||account.mode()!=PaymentAccountMode.SANDBOX||account.status()!=PaymentAccountStatus.ACTIVE
                ||!StripeBillingTestClockAdapter.API_VERSION.equals(owner.apiVersion()))throw unavailable();
            stage.accept("CLOCK_ADVANCE_RESERVE");
            var reserved=operations.reserveOwnedFirstRenewalAdvance(account,advance);
            requireStep(admission,advance,reserved);
            // Only a fresh claim confers continuation. A loser never stops the original winner.
            if(reserved.status()!=ProviderOperation.Status.RESERVED||reserved.firstDispatchedAt()!=null)throw unavailable();
            stage.accept("CLOCK_ADVANCE_CLAIM");
            var acquired=operations.claim(reserved.id(),claimant,utc.instant(),Duration.ofSeconds(60));
            if(acquired.isEmpty())throw unavailable();
            claim=acquired.get();
        } catch(RuntimeException failure) {
            stopKnownDrift(admission,failure);throw failure;
        }
        var budget=new Budget(began,claim.leaseExpiresAt());
        try {
            requireStep(admission,advance,claim);current(admission);
            var guard=new ProviderOperationGuard();guard.requireDispatch(claim,account,advance.fingerprint(),utc.instant());
            stage.accept("CLOCK_ADVANCE_CLIENT");
            var client=clients.create(credentials.resolve(account));
            long predecessor=step==Step.BOUNDARY?admission.receipt().intent().clock().frozenTime():boundary.ready().observation().frozenTime();
            stage.accept("CLOCK_ADVANCE_PRE_READ");
            var before=io(admission,budget,null,o->client.v1().testHelpers().testClocks().retrieve(admission.receipt().intent().clock().reference(),o));
            stage.accept("CLOCK_ADVANCE_PRE_VALIDATE");
            exactReady(admission,before,predecessor);
            String key=guard.requireDispatch(claim,account,advance.fingerprint(),utc.instant());
            stage.accept("CLOCK_ADVANCE_POST");
            var post=io(admission,budget,key,o->client.v1().testHelpers().testClocks().advance(
                admission.receipt().intent().clock().reference(),TestClockAdvanceParams.builder().setFrozenTime(advance.target()).build(),o));
            stage.accept("CLOCK_ADVANCE_POST_VALIDATE");
            var observation=ack(admission,post);
            if(observation.frozenTime()<predecessor||observation.frozenTime()>advance.target()
                    ||observation.advancingTarget()!=null&&observation.advancingTarget()!=advance.target())throw unavailable();
            stage.accept("CLOCK_ADVANCE_ACK_STORE");
            current(admission);budget.remaining();
            var acknowledged=operations.acknowledgeOwnedFirstRenewalAdvance(advance,claim,observation,utc.instant());
            if(!acknowledged.claimant().equals(claimant)||!acknowledged.deadline().equals(claim.leaseExpiresAt()))throw unavailable();
            for(int poll=0;poll<12;poll++) {
                stage.accept("CLOCK_ADVANCE_POLL_WAIT");
                budget.space(poll);
                stage.accept("CLOCK_ADVANCE_POLL_READ");
                var remote=io(admission,budget,null,o->client.v1().testHelpers().testClocks().retrieve(admission.receipt().intent().clock().reference(),o));
                stage.accept("CLOCK_ADVANCE_POLL_VALIDATE");
                var observed=ack(admission,remote);
                if(observed.advancingTarget()!=null&&observed.advancingTarget()!=advance.target()
                        ||observed.frozenTime()<predecessor||observed.frozenTime()>advance.target())throw unavailable();
                if("ready".equals(observed.status())) {
                    var ready=exactReady(admission,remote,advance.target());
                    stage.accept("CLOCK_ADVANCE_READY_STORE");
                    current(admission);budget.remaining();
                    var saved=renewals.recordReady(acknowledged,ready,utc.instant());
                    // Refuse a late successful return; a store write may already have committed.
                    budget.remaining();return saved;
                }
            }
            stage.accept("CLOCK_ADVANCE_POLL_EXHAUSTED");
            throw unavailable();
        } catch(RuntimeException failure) {
            try{operations.unknown(claim.id(),claimant,claim.version());}catch(RuntimeException ignored){}
            try{renewals.stop(admission,failure instanceof AuthorityDrift?"AUTHORITY_DRIFT":"ADVANCE_UNKNOWN",utc.instant());}catch(RuntimeException ignored){}
            throw failure;
        }
    }
    private void stopKnownDrift(Admission admission,RuntimeException original) {
        boolean drift=original instanceof AuthorityDrift;
        if(!drift)try{renewals.requireCurrent(admission);}catch(AuthorityDrift changed){drift=true;}catch(RuntimeException ignored){}
        if(drift)try{renewals.stop(admission,"AUTHORITY_DRIFT",utc.instant());}catch(RuntimeException ignored){}
    }
    private void current(Admission admission) {if(!admission.equals(renewals.requireCurrent(admission)))throw unavailable();}
    private static void requireStep(Admission a,Advance advance,ProviderOperation operation) {
        var owner=a.receipt().intent().clock().owner();
        if(operation.kind()!=ProviderOperation.Kind.TEST_CLOCK_ADVANCE||!advance.admissionId().equals(a.id())
                ||!operation.resourceId().equals(advance.id())||!operation.accountId().equals(owner.account())
                ||!operation.externalAccountId().equals(owner.externalAccount())||!operation.credentialsRef().equals(owner.credentialsRef())
                ||!operation.operationKey().equals(advance.operationKey())||!operation.fingerprint().equals(advance.fingerprint()))throw unavailable();
    }
    private static TestClockAck ack(Admission a,TestClock remote) {
        var original=a.receipt().intent().clock();
        if(remote==null||Boolean.TRUE.equals(remote.getDeleted())||!Boolean.FALSE.equals(remote.getLivemode())
                ||!original.reference().equals(remote.getId())||!original.owner().name().equals(remote.getName())
                ||!Objects.equals(original.providerCreated(),remote.getCreated())||!Objects.equals(original.providerDeletesAfter(),remote.getDeletesAfter())
                ||remote.getFrozenTime()==null)throw unavailable();
        Long target=remote.getStatusDetails()==null||remote.getStatusDetails().getAdvancing()==null?null:remote.getStatusDetails().getAdvancing().getTargetFrozenTime();
        return new TestClockAck(remote.getId(),remote.getObject(),false,remote.getName(),remote.getFrozenTime(),remote.getStatus(),remote.getCreated(),remote.getDeletesAfter(),target);
    }
    private static TestClockReady exactReady(Admission a,TestClock remote,long target) {
        var r=ack(a,remote);
        if(r.frozenTime()!=target)throw unavailable();
        return new TestClockReady(r.reference(),r.object(),r.livemode(),r.name(),r.frozenTime(),r.status(),r.created(),r.deletesAfter(),r.advancingTarget());
    }
    @FunctionalInterface private interface SdkCall<T> {T run(RequestOptions options)throws Exception;}
    private <T>T io(Admission admission,Budget budget,String key,SdkCall<T> call) {
        current(admission);
        long callStarted=nanos.getAsLong();long allowance=Math.min(TimeUnit.SECONDS.toNanos(2),budget.remaining());
        int millis=(int)TimeUnit.NANOSECONDS.toMillis(allowance);
        if(millis<=0)throw unavailable();
        var builder=RequestOptions.builder().setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
        RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,StripeBillingTestClockAdapter.API_VERSION);
        if(key!=null)builder.setIdempotencyKey(key);
        var options=builder.build();
        // Worker receives only one SDK call. No store, ACK, READY or callback continuation is transferred.
        var pending=new FutureTask<T>(()->{
            if(Thread.currentThread().isInterrupted()||nanos.getAsLong()-callStarted>=allowance)throw unavailable();
            return call.run(options);
        });
        Thread.ofVirtual().name("owned-renewal-sdk").start(pending);
        try {
            long remaining=Math.min(allowance-(nanos.getAsLong()-callStarted),budget.remaining());
            if(remaining<=0)throw unavailable();
            T result=pending.get(remaining,TimeUnit.NANOSECONDS);
            if(nanos.getAsLong()-callStarted>=allowance)throw unavailable();
            budget.remaining();current(admission);budget.remaining();return result;
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(TimeoutException timeout){throw unavailable();}
        catch(ExecutionException failed){if(failed.getCause() instanceof Error error)throw error;throw unavailable();}
        finally {pending.cancel(true);}
    }
    private final class Budget {
        private final long began;private final Instant deadline;
        Budget(long began,Instant deadline){this.began=began;this.deadline=Objects.requireNonNull(deadline);}
        long remaining(){
            long monotonic=TimeUnit.SECONDS.toNanos(30)-(nanos.getAsLong()-began);
            long lease=Duration.between(utc.instant(),deadline).toNanos();
            long remaining=Math.min(monotonic,lease);
            if(Thread.currentThread().isInterrupted()||remaining<=0)throw unavailable();return remaining;
        }
        void space(int poll){
            long remaining=remaining();
            long minimum=TimeUnit.MILLISECONDS.toNanos(250);
            // Keep the early-ready path; spread later reads within this original budget,
            // reserving the existing SDK allowance for the last read.
            long delay=poll==0?minimum:Math.max(minimum,(remaining-TimeUnit.SECONDS.toNanos(2))/(12-poll));
            if(delay>=remaining)throw unavailable();
            wait.accept(Duration.ofNanos(delay));
            remaining();
        }
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("Owned first renewal advance authority unavailable");}
}
