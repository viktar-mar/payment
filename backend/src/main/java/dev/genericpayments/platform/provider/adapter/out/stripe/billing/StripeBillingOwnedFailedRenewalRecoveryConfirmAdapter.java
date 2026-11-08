package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentConfirmParams;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import dev.genericpayments.platform.provider.application.service.OwnedRecoveryConfirmService;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Explicit one-use original-intent producer. A provider ACK never grants settlement authority. */
public final class StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter {
    private static final long CALL_NANOS=TimeUnit.SECONDS.toNanos(2);
    private static final com.fasterxml.jackson.databind.ObjectMapper RESPONSE_JSON=new com.fasterxml.jackson.databind.ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final OwnedFailedRenewalMethodService methods;
    private final StripeBillingOwnedFailedRenewalRecoveryReader remotePreflight;
    private final OwnedRecoveryConfirmService confirmations;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final DigestPort digest;
    private final Clock utc;
    private final LongSupplier nanos;

    public StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter(OwnedFailedRenewalMethodService methods,
            StripeBillingOwnedFailedRenewalRecoveryReader remotePreflight,OwnedRecoveryConfirmService confirmations,
            StripeCredentialResolver credentials,StripeClientFactory clients,DigestPort digest,Clock utc){
        this(methods,remotePreflight,confirmations,credentials,clients,digest,utc,System::nanoTime);
    }
    StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter(OwnedFailedRenewalMethodService methods,
            StripeBillingOwnedFailedRenewalRecoveryReader remotePreflight,OwnedRecoveryConfirmService confirmations,
            StripeCredentialResolver credentials,StripeClientFactory clients,DigestPort digest,Clock utc,LongSupplier nanos){
        this.methods=Objects.requireNonNull(methods);this.remotePreflight=Objects.requireNonNull(remotePreflight);
        this.confirmations=Objects.requireNonNull(confirmations);this.credentials=Objects.requireNonNull(credentials);
        this.clients=Objects.requireNonNull(clients);this.digest=Objects.requireNonNull(digest);
        this.utc=Objects.requireNonNull(utc);this.nanos=Objects.requireNonNull(nanos);
    }
    public ConfirmationAck confirm(Reservation reservation,Completion completion,Recovery recovery,SubscriptionCommandContext originalContext){
        RecoveryPreparationContext original=null;ProviderOperation owned=null;
        final InvocationBudget budget;
        try{budget=new InvocationBudget(utc,nanos);}catch(RuntimeException failure){throw unavailable();}
        try{
            budget.remainingNanos(null);
            var prepared=remotePreflight.inspect(reservation,completion,recovery,originalContext,budget);
            original=prepared.original();checkpoint(budget,null,original);
            if(!reservation.equals(original.reservation())||!completion.equals(original.completion())||!recovery.equals(original.recovery())
                    ||!StripeBillingTestClockAdapter.API_VERSION.equals(reservation.admission().firstRenewalAdmission().receipt().intent().clock().owner().apiVersion()))throw unavailable();
            if(!original.equals(methods.inspectBrowserRecoveryPreparation(reservation,completion,recovery,originalContext)))throw unavailable();
            checkpoint(budget,null,original);
            // Credentials and client construction are caller-side work within the same original budget.
            var resolved=credentials.resolve(original.account());checkpoint(budget,null,original);
            var client=clients.create(resolved);checkpoint(budget,null,original);
            String fingerprint=OwnedRecoveryConfirmService.fingerprint(original,digest);
            var claim=confirmations.claim(original,originalContext,UUID.randomUUID().toString(),budget);
            // Only the service's successfully returned independently committed first claim owns cleanup.
            owned=claim;checkpoint(budget,claim,original);
            if(!fingerprint.equals(claim.fingerprint())||claim.kind()!=ProviderOperation.Kind.OWNED_RECOVERY_INTENT_CONFIRM
                    ||!original.recoveryOperation().id().equals(claim.resourceId())
                    ||!(original.recoveryOperation().providerOperationKey()+":CONFIRM").equals(claim.operationKey()))throw unavailable();
            if(!claim.equals(confirmations.inspectClaim(original,claim,originalContext,budget)))throw unavailable();
            checkpoint(budget,claim,original);
            String key=new ProviderOperationGuard().requireDispatch(claim,original.account(),fingerprint,utc.instant());
            var params=PaymentIntentConfirmParams.builder().setPaymentMethod(completion.method()).setOffSession(true).setErrorOnRequiresAction(true).build();
            var remote=call(budget,claim,original.enrollment().sessionExpiresAt(),key,
                options->client.v1().paymentIntents().confirm(reservation.failure().paymentIntent(),params,options));
            checkpoint(budget,claim,original);
            var reply=reply(remote,original,utc.instant());checkpoint(budget,claim,original);
            // Success changes the remote graph. Only fresh local original authority and the exact
            // claim/reply are checked by this atomic ACK; the independent observer owns settlement.
            return confirmations.acknowledge(original,claim,reply,originalContext,budget);
        }catch(RuntimeException failure){
            if(owned!=null&&original!=null)try{confirmations.uncertain(original,owned,budget);}catch(RuntimeException ignored){/* Keep consumed evidence. */}
            throw unavailable();
        }
    }
    private void checkpoint(InvocationBudget budget,ProviderOperation claim,RecoveryPreparationContext original){
        budget.remainingNanos(claim);
        if(!utc.instant().isBefore(original.enrollment().sessionExpiresAt()))throw unavailable();
    }
    private ConfirmReply reply(PaymentIntent remote,RecoveryPreparationContext original,Instant observedAt){
        envelope(remote);
        var money=original.subscription().billingPrice().regularAmount();var amount=Long.valueOf(money.minorUnits());
        if(remote==null||!original.reservation().failure().paymentIntent().equals(remote.getId())||!"payment_intent".equals(remote.getObject())
                ||!Boolean.FALSE.equals(remote.getLivemode())||!"succeeded".equals(remote.getStatus())
                ||!original.binding().providerReference().equals(remote.getCustomer())||!original.completion().method().equals(remote.getPaymentMethod())
                ||!money.currency().toLowerCase(Locale.ROOT).equals(remote.getCurrency())||!amount.equals(remote.getAmount())
                ||!amount.equals(remote.getAmountReceived())||!Long.valueOf(0).equals(remote.getAmountCapturable())
                ||!"automatic".equals(remote.getCaptureMethod())||!List.of("card").equals(remote.getPaymentMethodTypes())
                ||remote.getLastPaymentError()!=null||remote.getNextAction()!=null)throw unavailable();
        return new ConfirmReply(remote.getId(),remote.getCustomer(),remote.getPaymentMethod(),money.currency(),money.minorUnits(),
            remote.getAmountReceived(),remote.getAmountCapturable(),remote.getLatestCharge(),observedAt);
    }
    /** Check unlogged wire types before SDK getters can coerce values or hide duplicate fields.
     * This fixed no-expand request supports textual references; general expanded Stripe objects
     * require a separately validated normalization contract before this lane can accept them. */
    private static void envelope(PaymentIntent remote){
        if(remote==null||remote.getLastResponse()==null)throw unavailable();
        try{
            var raw=RESPONSE_JSON.readTree(remote.getLastResponse().body());if(raw==null||!raw.isObject())throw unavailable();
            for(String field:List.of("id","object","customer","payment_method","currency","status","capture_method","latest_charge"))
                if(!raw.path(field).isTextual())throw unavailable();
            for(String field:List.of("amount","amount_received","amount_capturable"))
                if(!raw.path(field).isIntegralNumber()||!raw.path(field).canConvertToLong())throw unavailable();
            if(!raw.path("livemode").isBoolean()||!raw.path("payment_method_types").isArray())throw unavailable();
            for(var type:raw.path("payment_method_types"))if(!type.isTextual())throw unavailable();
            // Existing succeeded-response semantics accept an absent or explicit null error/action.
            // Every nonnull representation is unsupported, even if SDK deserialization hides it.
            for(String field:List.of("last_payment_error","next_action"))if(raw.has(field)&&!raw.get(field).isNull())throw unavailable();
        }catch(java.io.IOException invalid){throw unavailable();}
    }
    private interface Call<T>{T run(RequestOptions options)throws StripeException;}
    private <T>T call(InvocationBudget budget,ProviderOperation claim,Instant sessionExpiry,String key,Call<T> work){
        // Capture all call clocks before scheduling; no worker can acquire new authority or time.
        long actualStart=System.nanoTime(),injectedStart=nanos.getAsLong();var entered=utc.instant();
        long allowance=Math.min(CALL_NANOS,remaining(budget,claim,sessionExpiry));var deadline=entered.plusNanos(allowance);
        LongSupplier left=()->{
            long remaining=remaining(budget,claim,sessionExpiry),actualElapsed=System.nanoTime()-actualStart,injectedElapsed=nanos.getAsLong()-injectedStart;
            if(actualElapsed<0||injectedElapsed<0)throw unavailable();
            remaining=Math.min(remaining,Math.min(allowance-actualElapsed,Math.min(allowance-injectedElapsed,Duration.between(utc.instant(),deadline).toNanos())));
            if(remaining<=0)throw unavailable();return remaining;
        };
        int millis=(int)TimeUnit.NANOSECONDS.toMillis(left.getAsLong());if(millis<=0)throw unavailable();
        var optionsBuilder=RequestOptions.builder().setIdempotencyKey(key).setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
        RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(optionsBuilder,StripeBillingTestClockAdapter.API_VERSION);
        var options=optionsBuilder.build();
        var pending=new FutureTask<T>(()->{left.getAsLong();return work.run(options);});
        left.getAsLong();Thread.ofVirtual().name("owned-recovery-confirm-write").start(pending);
        try{
            T result=pending.get(left.getAsLong(),TimeUnit.NANOSECONDS);left.getAsLong();return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException uncertain){throw unavailable();}
        finally{pending.cancel(true);}
    }
    private long remaining(InvocationBudget budget,ProviderOperation claim,Instant sessionExpiry){
        long left=budget.remainingNanos(claim);left=Math.min(left,Duration.between(utc.instant(),sessionExpiry).toNanos());
        if(left<=0)throw unavailable();return left;
    }
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("OWNED_RECOVERY_CONFIRM_UNAVAILABLE",409,"Owned recovery confirmation unavailable");}
    @Override public String toString(){return "StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter[redacted]";}
}
