package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.param.SubscriptionUpdateParams;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedMethodWrite.Budget;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.LongSupplier;

/** Explicit one-use original subscription method writer. Returns evidence only, never lifecycle authority. */
public final class StripeBillingOwnedFailedRenewalMethodAdapter {
    private static final Duration LEASE=Duration.ofSeconds(60);
    private final OwnedFailedRenewalMethodService methods;
    private final ProviderOperationStore steps;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final DigestPort digest;
    private final Clock utc;
    private final LongSupplier nanos;
    public StripeBillingOwnedFailedRenewalMethodAdapter(OwnedFailedRenewalMethodService methods,ProviderOperationStore steps,
            StripeCredentialResolver credentials,StripeClientFactory clients,DigestPort digest,Clock utc){
        this(methods,steps,credentials,clients,digest,utc,System::nanoTime);
    }
    StripeBillingOwnedFailedRenewalMethodAdapter(OwnedFailedRenewalMethodService methods,ProviderOperationStore steps,
            StripeCredentialResolver credentials,StripeClientFactory clients,DigestPort digest,Clock utc,LongSupplier nanos){
        this.methods=Objects.requireNonNull(methods);this.steps=Objects.requireNonNull(steps);this.credentials=Objects.requireNonNull(credentials);
        this.clients=Objects.requireNonNull(clients);this.digest=Objects.requireNonNull(digest);this.utc=Objects.requireNonNull(utc);this.nanos=Objects.requireNonNull(nanos);
    }
    public ProviderOperation apply(Reservation reservation,SubscriptionCommandContext originalContext){
        ProviderOperation owned=null;
        try{
            var budget=new Budget(utc,nanos);budget.remaining(null);
            var original=methods.inspectBrowserMethodPreparation(reservation,originalContext);
            var p=original.original();var account=p.account();var operation=p.operation();var subscription=p.subscription();
            if(!StripeBillingTestClockAdapter.API_VERSION.equals(reservation.admission().firstRenewalAdmission().receipt().intent().clock().owner().apiVersion()))throw unavailable();
            String key=operation.providerOperationKey()+":METHOD",fingerprint=fingerprint(original);
            budget.remaining(null);
            if(steps.find(account.id(),key).isPresent())throw unavailable();
            inspect(original,originalContext,budget,null);
            dispatchTime(original.setupStep());
            var reserved=steps.reserveOwnedFailedRenewalMethodStep(account,reservation,ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
            requireIdentity(reserved,original,fingerprint);
            if(reserved.status()!=ProviderOperation.Status.RESERVED||reserved.version()!=0||reserved.providerReference()!=null
                    ||reserved.firstDispatchedAt()!=null||reserved.retryBefore()!=null||reserved.leaseOwner()!=null||reserved.leaseExpiresAt()!=null)throw unavailable();
            inspect(original,originalContext,budget,null);
            String claimant=UUID.randomUUID().toString();Instant claimedAt=dispatchTime(original.setupStep());
            var claim=steps.claim(reserved.id(),claimant,claimedAt,LEASE).orElseThrow(StripeBillingOwnedFailedRenewalMethodAdapter::unavailable);
            requireIdentity(claim,original,fingerprint);
            if(!reserved.id().equals(claim.id())||claim.status()!=ProviderOperation.Status.DISPATCHING||claim.version()!=1
                    ||!claimant.equals(claim.leaseOwner())||claim.providerReference()!=null||!claimedAt.equals(claim.firstDispatchedAt())
                    ||!claimedAt.plus(LEASE).equals(claim.leaseExpiresAt())||!claimedAt.plus(Duration.ofHours(23)).equals(claim.retryBefore()))throw unavailable();
            // Insert ownership is irrelevant; only this exact first original dispatch owns cleanup.
            owned=claim;
            inspect(original,originalContext,budget,claim);
            var dispatch=steps.requireOwnedFailedRenewalMethodDispatch(account,reservation,claim,utc.instant());
            if(!claim.equals(dispatch))throw unavailable();
            inspect(original,originalContext,budget,claim);
            var params=SubscriptionUpdateParams.builder().setDefaultPaymentMethod(original.setupProof().method()).build();
            var client=clients.create(credentials.resolve(account));
            inspect(original,originalContext,budget,claim);
            String dispatchKey=new ProviderOperationGuard().requireDispatch(dispatch,account,fingerprint,utc.instant());
            var remote=budget.call(claim,p.enrollment().sessionExpiresAt(),dispatchKey,options->client.v1().subscriptions().update(subscription.providerReference(),params,options));
            if(remote==null||!subscription.providerReference().equals(remote.getId())||!"subscription".equals(remote.getObject())
                    ||!Boolean.FALSE.equals(remote.getLivemode())||!p.binding().providerReference().equals(remote.getCustomer())
                    ||!original.setupProof().method().equals(remote.getDefaultPaymentMethod()))throw unavailable();
            inspect(original,originalContext,budget,claim);
            if(!claim.equals(steps.requireOwnedFailedRenewalMethodDispatch(account,reservation,claim,utc.instant())))throw unavailable();
            inspect(original,originalContext,budget,claim);
            new ProviderOperationGuard().requireDispatch(claim,account,fingerprint,utc.instant());
            var ack=steps.acknowledge(claim.id(),claimant,claim.version(),subscription.providerReference());
            requireIdentity(ack,original,fingerprint);
            if(!claim.id().equals(ack.id())||ack.status()!=ProviderOperation.Status.ACKNOWLEDGED||ack.version()!=2
                    ||!subscription.providerReference().equals(ack.providerReference())||!claim.firstDispatchedAt().equals(ack.firstDispatchedAt())
                    ||!claim.retryBefore().equals(ack.retryBefore())||ack.leaseOwner()!=null||ack.leaseExpiresAt()!=null)throw unavailable();
            // The ACK may commit after last authenticated inspection. It grants no lifecycle authority;
            // the separate recordMethodUpdateReply transaction must authenticate again.
            return ack;
        }catch(RuntimeException failure){
            if(owned!=null)StripeBillingOwnedMethodWrite.terminate(methods,steps,reservation,owned);
            throw unavailable();
        }
    }
    private void inspect(MethodPreparationContext expected,SubscriptionCommandContext context,Budget budget,ProviderOperation claim){
        budget.remaining(claim);
        if(!expected.equals(methods.inspectBrowserMethodPreparation(expected.original().reservation(),context)))throw unavailable();
        budget.remaining(claim);
    }
    private Instant dispatchTime(ProviderOperation setup){
        var at=utc.instant().truncatedTo(ChronoUnit.MICROS);
        if(at.isBefore(setup.firstDispatchedAt()))throw unavailable();
        return at;
    }
    private String fingerprint(MethodPreparationContext context){var p=context.original();var o=p.operation();var proof=context.setupProof();return StripeRequestFingerprint.of(digest,p.account(),"SUBSCRIPTION_METHOD_V1",o.id().toString(),o.providerOperationKey()+":METHOD",o.fingerprint(),p.subscription().providerReference(),p.binding().providerReference(),proof.step().toString(),proof.reference(),proof.method(),"consent:true");}
    private static void requireIdentity(ProviderOperation step,MethodPreparationContext context,String fingerprint){
        var p=context.original();var account=p.account();var operation=p.operation();
        if(step==null||step.id()==null||step.kind()!=ProviderOperation.Kind.SUBSCRIPTION_UPDATE||!account.id().equals(step.accountId())
                ||!account.externalAccountId().equals(step.externalAccountId())||!account.credentialsRef().equals(step.credentialsRef())
                ||!operation.id().equals(step.resourceId())||!(operation.providerOperationKey()+":METHOD").equals(step.operationKey())||!fingerprint.equals(step.fingerprint()))throw unavailable();
    }
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("OWNED_METHOD_APPLY_UNAVAILABLE",409,"Owned method application unavailable");}
    @Override public String toString(){return "StripeBillingOwnedFailedRenewalMethodAdapter[redacted]";}
}
