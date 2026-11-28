package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Explicit original confirmed-method reader. Independent GET evidence precedes authenticated atomic binding. */
public final class StripeBillingOwnedFailedRenewalBindingAdapter {
    private final OwnedFailedRenewalMethodService methods;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final Clock utc;
    private final LongSupplier nanos;

    public StripeBillingOwnedFailedRenewalBindingAdapter(OwnedFailedRenewalMethodService methods,
            StripeCredentialResolver credentials,StripeClientFactory clients,Clock utc){
        this(methods,credentials,clients,utc,System::nanoTime);
    }
    StripeBillingOwnedFailedRenewalBindingAdapter(OwnedFailedRenewalMethodService methods,
            StripeCredentialResolver credentials,StripeClientFactory clients,Clock utc,LongSupplier nanos){
        this.methods=Objects.requireNonNull(methods);this.credentials=Objects.requireNonNull(credentials);
        this.clients=Objects.requireNonNull(clients);this.utc=Objects.requireNonNull(utc);this.nanos=Objects.requireNonNull(nanos);
    }

    public Setup bind(Reservation reservation,SubscriptionCommandContext originalContext){
        try{
            var reads=new Reads(utc,nanos);reads.remaining(null);
            var original=methods.inspectBrowserBindingPreparation(reservation,originalContext);
            var p=original.original();var expiry=p.enrollment().sessionExpiresAt();
            if(!StripeBillingTestClockAdapter.API_VERSION.equals(reservation.admission().firstRenewalAdmission().receipt().intent().clock().owner().apiVersion()))throw unavailable();
            reads.remaining(expiry);
            var credential=credentials.resolve(p.account());reads.remaining(expiry);
            var client=clients.create(credential);
            inspect(original,originalContext,reads);
            var setup=reads.get(expiry,options->client.v1().setupIntents().retrieve(original.setupProof().reference(),options));
            StripeSubscriptionProviderAdapter.requireSetupRemote(setup,p.subscription(),p.operation(),p.binding(),original.setupStep());
            String method=setup.getPaymentMethod();
            if(!"succeeded".equals(setup.getStatus())||method==null||!method.matches("pm_[A-Za-z0-9]{1,252}"))throw unavailable();
            inspect(original,originalContext,reads);
            var card=reads.get(expiry,options->client.v1().paymentMethods().retrieve(method,options));
            if(card==null||!method.equals(card.getId())||!"payment_method".equals(card.getObject())||!Boolean.FALSE.equals(card.getLivemode())
                    ||!p.binding().providerReference().equals(card.getCustomer())||!"card".equals(card.getType())||card.getCard()==null)throw unavailable();
            inspect(original,originalContext,reads);
            // The separate service transaction reauthenticates and conditionally binds this exact unbound proof.
            // Return its real committed result even if the last valid check is followed by expiry or revocation.
            return methods.bindBrowserMethod(original,originalContext,method);
        }catch(RuntimeException failure){throw unavailable();}
    }

    private void inspect(BindingPreparationContext expected,SubscriptionCommandContext context,Reads reads){
        var expiry=expected.original().enrollment().sessionExpiresAt();reads.remaining(expiry);
        if(!expected.equals(methods.inspectBrowserBindingPreparation(expected.original().reservation(),context)))throw unavailable();
        reads.remaining(expiry);
    }

    /** Read-only capabilities: SDK function, clocks and cancellation. No service, store, lease or write cleanup. */
    private static final class Reads {
        private static final long INVOCATION=TimeUnit.SECONDS.toNanos(30),PER_GET=TimeUnit.SECONDS.toNanos(2);
        private final Clock utc;
        private final LongSupplier nanos;
        private final long enteredActual,enteredInjected;
        private final Instant enteredUtc,deadline;
        Reads(Clock utc,LongSupplier nanos){
            this.utc=utc;this.nanos=nanos;enteredActual=System.nanoTime();enteredInjected=nanos.getAsLong();
            enteredUtc=utc.instant();deadline=enteredUtc.plusSeconds(30);
        }
        long remaining(Instant expiry){
            var now=utc.instant();
            long left=Math.min(elapsedLimit(enteredActual,enteredInjected,INVOCATION),until(now,enteredUtc,deadline));
            if(expiry!=null)left=Math.min(left,Duration.between(now,expiry).toNanos());
            if(left<=0)throw unavailable();return left;
        }
        <T>T get(Instant expiry,SdkGet<T> sdk){
            long actual=System.nanoTime(),injected=nanos.getAsLong();var start=utc.instant();var end=start.plusSeconds(2);
            var call=new ReadCall(expiry,actual,injected,start,end);
            int millis=(int)TimeUnit.NANOSECONDS.toMillis(call.remaining());if(millis<=0)throw unavailable();
            var builder=RequestOptions.builder().setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
            RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,StripeBillingTestClockAdapter.API_VERSION);
            var options=builder.build();
            var result=new FutureTask<T>(()->{call.remaining();return sdk.retrieve(options);});
            call.remaining();Thread.ofVirtual().name("owned-method-binding-read").start(result);
            try{var remote=result.get(call.remaining(),TimeUnit.NANOSECONDS);call.remaining();return remote;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
            catch(ExecutionException|TimeoutException failure){throw unavailable();}
            finally{result.cancel(true);}
        }
        private long elapsedLimit(long actualStart,long injectedStart,long limit){
            long actual=System.nanoTime()-actualStart,injected=nanos.getAsLong()-injectedStart;
            if(Thread.currentThread().isInterrupted()||actual<0||injected<0)throw unavailable();
            return Math.min(limit-actual,limit-injected);
        }
        private static long until(Instant now,Instant start,Instant end){
            if(now.isBefore(start))throw unavailable();return Duration.between(now,end).toNanos();
        }
        private final class ReadCall {
            private final Instant expiry,start,end;private final long actual,injected;
            ReadCall(Instant expiry,long actual,long injected,Instant start,Instant end){this.expiry=expiry;this.actual=actual;this.injected=injected;this.start=start;this.end=end;}
            long remaining(){
                long left=Math.min(Reads.this.remaining(expiry),Math.min(elapsedLimit(actual,injected,PER_GET),until(utc.instant(),start,end)));
                if(left<=0)throw unavailable();return left;
            }
        }
    }
    @FunctionalInterface private interface SdkGet<T> {T retrieve(RequestOptions options)throws StripeException;}
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("OWNED_METHOD_BINDING_UNAVAILABLE",409,"Owned method binding unavailable");}
    @Override public String toString(){return "StripeBillingOwnedFailedRenewalBindingAdapter[redacted]";}
}
