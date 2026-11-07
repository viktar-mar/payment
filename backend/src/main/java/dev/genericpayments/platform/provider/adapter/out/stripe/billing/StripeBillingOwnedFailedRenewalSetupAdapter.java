package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.SetupIntentCreateParams;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Explicit original enrolled SETUP producer. No runtime registration, retries, or existing-step adoption.
 * All authority/receipt work stays on the caller; the cancellable worker owns only one SDK create. */
public final class StripeBillingOwnedFailedRenewalSetupAdapter {
    private static final Duration LEASE=Duration.ofSeconds(60);
    private static final long INVOCATION=TimeUnit.SECONDS.toNanos(30),CALL=TimeUnit.SECONDS.toNanos(2);
    private final OwnedFailedRenewalMethodService methods;
    private final ProviderOperationStore steps;
    private final SubscriptionBillingProofStore proofs;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final DigestPort digest;
    private final Clock utc;
    private final LongSupplier nanos;
    public StripeBillingOwnedFailedRenewalSetupAdapter(OwnedFailedRenewalMethodService methods,ProviderOperationStore steps,
            SubscriptionBillingProofStore proofs,StripeCredentialResolver credentials,StripeClientFactory clients,DigestPort digest,Clock utc){
        this(methods,steps,proofs,credentials,clients,digest,utc,System::nanoTime);
    }
    StripeBillingOwnedFailedRenewalSetupAdapter(OwnedFailedRenewalMethodService methods,ProviderOperationStore steps,
            SubscriptionBillingProofStore proofs,StripeCredentialResolver credentials,StripeClientFactory clients,DigestPort digest,Clock utc,LongSupplier nanos){
        this.methods=Objects.requireNonNull(methods);this.steps=Objects.requireNonNull(steps);this.proofs=Objects.requireNonNull(proofs);
        this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);this.digest=Objects.requireNonNull(digest);this.utc=Objects.requireNonNull(utc);this.nanos=Objects.requireNonNull(nanos);
    }
    public SubscriptionBillingProofStore.Setup prepare(Reservation reservation,SubscriptionCommandContext originalContext){
        ProviderOperation owned=null;
        try{
            var budget=new Budget();budget.remaining(null);
            var original=methods.inspectBrowserSetupPreparation(reservation,originalContext);
            var account=original.account();var operation=original.operation();var subscription=original.subscription();
            if(!StripeBillingTestClockAdapter.API_VERSION.equals(reservation.admission().firstRenewalAdmission().receipt().intent().clock().owner().apiVersion()))throw unavailable();
            String key=operation.providerOperationKey()+":SETUP",fingerprint=fingerprint(original);
            budget.remaining(null);
            if(proofs.setup(account.id(),operation.id()).isPresent()||steps.find(account.id(),key).isPresent())throw unavailable();
            budget.remaining(null);
            var reserved=steps.reserveOwnedFailedRenewalMethodStep(account,reservation,ProviderOperation.Kind.SETUP_INTENT_CREATE);
            requireIdentity(reserved,original,fingerprint);
            if(reserved.status()!=ProviderOperation.Status.RESERVED||reserved.version()!=0||reserved.providerReference()!=null
                    ||reserved.firstDispatchedAt()!=null||reserved.retryBefore()!=null||reserved.leaseOwner()!=null||reserved.leaseExpiresAt()!=null)throw unavailable();
            inspect(original,originalContext,budget,null);
            String claimant=UUID.randomUUID().toString();Instant claimedAt=utc.instant().truncatedTo(ChronoUnit.MICROS);
            var claim=steps.claim(reserved.id(),claimant,claimedAt,LEASE).orElseThrow(StripeBillingOwnedFailedRenewalSetupAdapter::unavailable);
            requireIdentity(claim,original,fingerprint);
            if(!reserved.id().equals(claim.id())||claim.status()!=ProviderOperation.Status.DISPATCHING||claim.version()!=1
                    ||!claimant.equals(claim.leaseOwner())||claim.providerReference()!=null||!claimedAt.equals(claim.firstDispatchedAt())
                    ||!claimedAt.plus(LEASE).equals(claim.leaseExpiresAt())||!claimedAt.plus(Duration.ofHours(23)).equals(claim.retryBefore()))throw unavailable();
            // A shared reserve does not confer ownership; only this exact original first claim does.
            owned=claim;
            inspect(original,originalContext,budget,claim);
            proofs.authorizeSetup(subscription,operation);
            inspect(original,originalContext,budget,claim);
            var dispatch=steps.requireOwnedFailedRenewalMethodDispatch(account,reservation,claim,utc.instant());
            if(!claim.equals(dispatch))throw unavailable();
            inspect(original,originalContext,budget,claim);
            var metadata=StripeBillingOwnership.metadata(account,subscription,operation);
            var params=SetupIntentCreateParams.builder().setCustomer(original.binding().providerReference())
                .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION).addAllowedPaymentMethodType(SetupIntentCreateParams.AllowedPaymentMethodType.CARD)
                .putAllMetadata(metadata).build();
            var client=clients.create(credentials.resolve(account));
            inspect(original,originalContext,budget,claim);
            String dispatchKey=new ProviderOperationGuard().requireDispatch(dispatch,account,fingerprint,utc.instant());
            long callStarted=System.nanoTime();Instant callDeadline=utc.instant().plusSeconds(2);
            int millis=(int)TimeUnit.NANOSECONDS.toMillis(callRemaining(budget,claim,callStarted,callDeadline));
            if(millis<=0)throw unavailable();
            var optionsBuilder=RequestOptions.builder().setIdempotencyKey(dispatchKey).setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
            RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(optionsBuilder,StripeBillingTestClockAdapter.API_VERSION);
            var options=optionsBuilder.build();
            Instant sessionExpiresAt=original.enrollment().sessionExpiresAt();
            var pending=new FutureTask<SetupIntent>(()->{
                // Scheduling cannot extend the immutable call/invocation/lease or original session.
                callRemaining(budget,claim,callStarted,callDeadline);
                if(!utc.instant().isBefore(sessionExpiresAt))throw unavailable();
                return client.v1().setupIntents().create(params,options);
            });
            SetupIntent remote;
            // The worker checks time/interruption only, then performs the one SDK create.
            callRemaining(budget,claim,callStarted,callDeadline);
            Thread.ofVirtual().name("owned-method-setup-create").start(pending);
            try{
                remote=pending.get(callRemaining(budget,claim,callStarted,callDeadline),TimeUnit.NANOSECONDS);
                callRemaining(budget,claim,callStarted,callDeadline);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
            catch(ExecutionException|TimeoutException uncertain){throw unavailable();}
            finally{pending.cancel(true);}
            StripeSubscriptionProviderAdapter.requireSetupRemote(remote,subscription,operation,original.binding(),claim);
            if(!"requires_payment_method".equals(remote.getStatus())||remote.getPaymentMethod()!=null||!metadata.equals(remote.getMetadata()))throw unavailable();
            inspect(original,originalContext,budget,claim);
            if(!claim.equals(steps.requireOwnedFailedRenewalMethodDispatch(account,reservation,claim,utc.instant())))throw unavailable();
            inspect(original,originalContext,budget,claim);
            new ProviderOperationGuard().requireDispatch(claim,account,fingerprint,utc.instant());
            var ack=steps.acknowledge(claim.id(),claimant,claim.version(),remote.getId());
            requireIdentity(ack,original,fingerprint);
            if(!claim.id().equals(ack.id())||ack.status()!=ProviderOperation.Status.ACKNOWLEDGED||ack.version()!=2
                    ||!remote.getId().equals(ack.providerReference())||!claim.firstDispatchedAt().equals(ack.firstDispatchedAt())
                    ||!claim.retryBefore().equals(ack.retryBefore())||ack.leaseOwner()!=null||ack.leaseExpiresAt()!=null)throw unavailable();
            inspect(original,originalContext,budget,claim);
            var proof=proofs.recordSetup(subscription,operation,ack.id(),remote.getId());
            if(proof==null||!operation.id().equals(proof.operation())||!subscription.id().equals(proof.subscription())
                    ||!account.id().equals(proof.account())||!ack.id().equals(proof.step())||!remote.getId().equals(proof.reference())||proof.method()!=null)throw unavailable();
            // This independent evidence commit may finish after session expiry/revocation. It grants
            // no browser authority, and must be returned truthfully without another deadline check.
            return proof;
        }catch(RuntimeException failure){
            if(owned!=null)terminate(reservation,owned);
            throw unavailable();
        }
    }
    private void inspect(SetupPreparationContext expected,SubscriptionCommandContext context,Budget budget,ProviderOperation claim){
        budget.remaining(claim);
        if(!expected.equals(methods.inspectBrowserSetupPreparation(expected.reservation(),context)))throw unavailable();
        budget.remaining(claim);
    }
    private String fingerprint(SetupPreparationContext p){var o=p.operation();var s=p.subscription();return StripeRequestFingerprint.of(digest,p.account(),"SETUP_INTENT_CREATE_V1",o.id().toString(),o.providerOperationKey()+":SETUP",o.fingerprint(),Long.toString(o.policyVersion()),s.id().toString(),s.siteId().value().toString(),s.customerId().value().toString(),p.binding().providerReference(),"off_session","card","consent:true");}
    private static void requireIdentity(ProviderOperation step,SetupPreparationContext p,String fingerprint){
        PaymentAccount a=p.account();var o=p.operation();
        if(step==null||step.id()==null||step.kind()!=ProviderOperation.Kind.SETUP_INTENT_CREATE||!a.id().equals(step.accountId())
                ||!a.externalAccountId().equals(step.externalAccountId())||!a.credentialsRef().equals(step.credentialsRef())
                ||!o.id().equals(step.resourceId())||!(o.providerOperationKey()+":SETUP").equals(step.operationKey())||!fingerprint.equals(step.fingerprint()))throw unavailable();
    }
    private void terminate(Reservation reservation,ProviderOperation claim){
        try{
            var current=steps.findById(claim.id());
            if(current.isPresent()&&claim.equals(current.get()))steps.unknown(claim.id(),claim.leaseOwner(),claim.version());
        }catch(RuntimeException ignored){/* An uncertain receipt cannot authorize replacement. */}
        try{methods.stop(reservation,OwnedFailedRenewal.Reason.UNCERTAIN);}
        catch(RuntimeException ignored){/* Preserve the original consumed claim even when stop persistence fails. */}
    }
    private long callRemaining(Budget budget,ProviderOperation claim,long started,Instant deadline){
        long elapsed=System.nanoTime()-started;
        long remaining=Math.min(budget.remaining(claim),Math.min(CALL-elapsed,Duration.between(utc.instant(),deadline).toNanos()));
        if(Thread.currentThread().isInterrupted()||elapsed<0||remaining<=0)throw unavailable();return remaining;
    }
    private final class Budget {
        private final long actualStart=System.nanoTime(),injectedStart=nanos.getAsLong();
        private final Instant entered=utc.instant(),deadline=entered.plusSeconds(30);
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
    }
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("OWNED_METHOD_SETUP_UNAVAILABLE",409,"Owned method setup unavailable");}
    @Override public String toString(){return "StripeBillingOwnedFailedRenewalSetupAdapter[redacted]";}
}
