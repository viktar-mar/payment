package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.Pending;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.OwnedMethodBrowserReader;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** One original SDK retrieve with no application/SDK retries and one wall/monotonic budget.
 * Existing JDK transparent GET recovery shares that budget; no single-wire-attempt claim.
 * The SDK worker has no service/store/transaction capability. */
public final class StripeOwnedMethodBrowserReader implements OwnedMethodBrowserReader {
    private static final long LIMIT=TimeUnit.SECONDS.toNanos(2);
    private final StripeCredentialResolver credentials;private final StripeClientFactory clients;private final Clock clock;
    public StripeOwnedMethodBrowserReader(StripeCredentialResolver credentials,StripeClientFactory clients,Clock clock){this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);this.clock=Objects.requireNonNull(clock);}
    public EphemeralSetup read(Pending p,Instant deadline){
        long started=System.nanoTime();
        try{
            if(deadline==null)throw unavailable();
            var maximum=clock.instant().plusSeconds(2);
            var wallDeadline=deadline.isBefore(maximum)?deadline:maximum;
            remaining(started,wallDeadline);requireOriginal(p);
            var credential=credentials.resolve(p.account());var pk=credential.publishableKey();
            if(pk==null||!pk.matches("pk_test_[A-Za-z0-9]+"))throw unavailable();
            var client=clients.create(credential);var reference=p.setup().reference();
            int millis=(int)TimeUnit.NANOSECONDS.toMillis(remaining(started,wallDeadline));if(millis<=0)throw unavailable();
            var builder=RequestOptions.builder().setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
            RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,StripeBillingTestClockAdapter.API_VERSION);
            var options=builder.build();
            var pending=new FutureTask<SetupIntent>(()->{remaining(started,wallDeadline);return client.v1().setupIntents().retrieve(reference,options);});
            Thread.ofVirtual().name("owned-method-browser-read").start(pending);
            SetupIntent remote;
            try{remote=pending.get(remaining(started,wallDeadline),TimeUnit.NANOSECONDS);remaining(started,wallDeadline);}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw unavailable();}
            catch(ExecutionException|TimeoutException failure){throw unavailable();}
            finally{pending.cancel(true);}
            StripeSubscriptionProviderAdapter.requireSetupRemote(remote,p.subscription(),p.operation(),p.binding(),p.setupStep());
            if(!reference.equals(remote.getId())||!"requires_payment_method".equals(remote.getStatus())||remote.getPaymentMethod()!=null)throw unavailable();
            var secret=remote.getClientSecret();
            if(secret==null||secret.length()>512||!secret.matches(Pattern.quote(reference)+"_secret_[A-Za-z0-9_]+"))throw unavailable();
            remaining(started,wallDeadline);
            return new EphemeralSetup(reference,pk,secret);
        }catch(RuntimeException failure){throw unavailable();}
    }
    private long remaining(long started,Instant deadline){
        if(deadline==null||Thread.currentThread().isInterrupted())throw unavailable();
        long elapsed=System.nanoTime()-started;long utc=Duration.between(clock.instant(),deadline).toNanos();
        long left=Math.min(LIMIT-elapsed,utc);if(elapsed<0||left<=0)throw unavailable();return left;
    }
    private static void requireOriginal(Pending p){
        if(p==null)throw unavailable();var a=p.account();var s=p.subscription();var o=p.operation();var b=p.binding();var step=p.setupStep();var proof=p.setup();var r=p.reservation();
        var owner=r.admission().firstRenewalAdmission().receipt().intent().clock().owner();
        if(a.provider()!=PaymentProviderType.STRIPE||a.mode()!=PaymentAccountMode.SANDBOX||a.status()!=PaymentAccountStatus.ACTIVE
            ||!a.id().equals(owner.account())||!a.externalAccountId().equals(owner.externalAccount())||!a.credentialsRef().equals(owner.credentialsRef())
            ||!StripeBillingTestClockAdapter.API_VERSION.equals(owner.apiVersion())||!b.equals(r.binding())||!a.id().equals(b.accountId())
            ||!s.paymentAccountId().equals(a.id())||!s.siteId().equals(b.siteId())||!s.customerId().equals(b.customerId())||!o.subscriptionId().equals(s.id())
            ||!o.id().equals(r.setupOperationId())||o.kind()!=SubscriptionOperation.Kind.SETUP||o.status()!=SubscriptionOperation.Status.RESERVED||o.version()!=0
            ||!proof.operation().equals(o.id())||!proof.subscription().equals(s.id())||!proof.account().equals(a.id())||!proof.step().equals(step.id())||proof.method()!=null
            ||proof.reference()==null||!proof.reference().matches("seti_[A-Za-z0-9]{1,250}")||!proof.reference().equals(step.providerReference())
            ||step.kind()!=ProviderOperation.Kind.SETUP_INTENT_CREATE||step.status()!=ProviderOperation.Status.ACKNOWLEDGED||step.version()!=2
            ||!step.accountId().equals(a.id())||!step.externalAccountId().equals(a.externalAccountId())||!step.credentialsRef().equals(a.credentialsRef())
            ||!step.resourceId().equals(o.id())||!step.operationKey().equals(o.providerOperationKey()+":SETUP")||step.firstDispatchedAt()==null||step.retryBefore()==null)throw unavailable();
    }
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("OWNED_METHOD_BROWSER_UNAVAILABLE",409,"Owned method browser unavailable");}
    @Override public String toString(){return "StripeOwnedMethodBrowserReader[redacted]";}
}
