package dev.genericpayments.acceptance.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.SubscriptionUpdateParams;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingTestClockAdapter;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Fixture-only SDK producer. Its descriptive ACK cannot publish or reserve financial facts. */
final class OwnedBillingFailedRenewalFailureArm {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Admission admission;private final Pending pending;
    private final Supplier<SubscriptionCommandContext> context;
    private final StripeCredentialResolver credentials;private final StripeClientFactory clients;
    private final LoadPaymentAccountPort accounts;private final OwnedFirstRenewalStore store;
    private final Clock utc;private final Instant deadline;private final LongSupplier nanos;
    private final AtomicBoolean consumed=new AtomicBoolean();
    private SubscriptionCommandContext first;private PaymentAccount account;private String originalDefault;
    private InvocationBudget budget;

    OwnedBillingFailedRenewalFailureArm(Admission admission,Pending pending,Supplier<SubscriptionCommandContext> context,
            StripeCredentialResolver credentials,StripeClientFactory clients,LoadPaymentAccountPort accounts,
            OwnedFirstRenewalStore store,Clock utc,Instant deadline){
        this(admission,pending,context,credentials,clients,accounts,store,utc,deadline,System::nanoTime);
    }
    OwnedBillingFailedRenewalFailureArm(Admission admission,Pending pending,Supplier<SubscriptionCommandContext> context,
            StripeCredentialResolver credentials,StripeClientFactory clients,LoadPaymentAccountPort accounts,
            OwnedFirstRenewalStore store,Clock utc,Instant deadline,LongSupplier nanos){
        this.admission=Objects.requireNonNull(admission);this.pending=Objects.requireNonNull(pending);this.context=Objects.requireNonNull(context);
        this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);this.accounts=Objects.requireNonNull(accounts);
        this.store=Objects.requireNonNull(store);this.utc=Objects.requireNonNull(utc);this.deadline=Objects.requireNonNull(deadline);this.nanos=Objects.requireNonNull(nanos);
        require(utc.getZone().equals(ZoneOffset.UTC));
    }
    FailureArmEvidence arm(){
        // A losing caller owns neither transport nor the first caller's cleanup.
        if(!consumed.compareAndSet(false,true))throw denied();
        try{
            budget=new InvocationBudget(utc,nanos);
            local();var client=clients.create(credentials.resolve(account));remaining();
            bookend(client,null);
            var method=call(options->client.v1().paymentMethods().attach("pm_card_chargeCustomerFail",
                PaymentMethodAttachParams.builder().setCustomer(intent().customerReference()).build(),options),key("attach"));
            require(text(method,"object").equals("payment_method")&&!bool(method,"livemode")&&text(method,"type").equals("card")
                &&text(method,"customer").equals(intent().customerReference()));
            String attached=text(method,"id");require(attached.matches("pm_[A-Za-z0-9]{1,252}")&&method.path("card").isObject());
            bookend(client,null);bookend(client,null);
            var updated=call(options->client.v1().subscriptions().update(admission.receipt().reference(),SubscriptionUpdateParams.builder()
                .setDefaultPaymentMethod(attached).setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE).build(),options),key("default"));
            subscription(updated,attached);bookend(client,attached);
            return new FailureArmEvidence(admission.id(),pending.boundary().id(),intent().clock().owner().run(),attached,utc.instant());
        }catch(RuntimeException problem){throw denied();}
    }
    private dev.genericpayments.platform.provider.application.model.OwnedClockSubscription intent(){return admission.receipt().intent();}
    private String key(String purpose){return "failed-renewal-arm-v1:"+intent().clock().owner().run()+":"+admission.id()+":"+purpose;}
    private void local(){
        remaining();var owner=intent().clock().owner();
        require(pending.admission().equals(admission)&&pending.ready().ack().advance().step()==Step.BOUNDARY
            &&pending.ready().equals(store.ready(admission,Step.BOUNDARY).orElseThrow())&&pending.boundary().equals(store.boundary(admission))
            &&owner.apiVersion().equals(StripeBillingTestClockAdapter.API_VERSION));
        var current=accounts.findById(owner.account()).orElseThrow();
        require(current.provider()==PaymentProviderType.STRIPE&&current.mode()==PaymentAccountMode.SANDBOX&&current.status()==PaymentAccountStatus.ACTIVE
            &&current.externalAccountId().equals(owner.externalAccount())&&current.externalAccountId().equals("acct_1UPiMpELswAd8tpR")&&current.credentialsRef().equals(owner.credentialsRef()));
        if(account==null)account=current;else require(account.equals(current));
        var actual=Objects.requireNonNull(context.get());
        require(actual.actor() instanceof SubscriptionActor.Customer&&actual.siteId().equals(owner.site())&&actual.customerId().equals(owner.customer()));
        var session=((SubscriptionActor.Customer)actual.actor()).session();
        require(session.siteId().equals(owner.site())&&session.customerId().equals(owner.customer())&&session.origin().equals(actual.origin())&&utc.instant().isBefore(session.expiresAt()));
        session.requireScope(CustomerSessionScope.SUBSCRIPTION_MANAGE);
        if(first==null)first=actual;else require(first.actor().equals(actual.actor())&&first.siteId().equals(actual.siteId())&&first.customerId().equals(actual.customerId())&&first.origin().equals(actual.origin()));
        remaining();
    }
    private void bookend(StripeClient client,String expectedDefault){
        local();var clock=intent().clock();var owner=clock.owner();
        var remote=call(options->client.v1().testHelpers().testClocks().retrieve(clock.reference(),options),null);
        require(text(remote,"id").equals(clock.reference())&&text(remote,"object").equals("test_helpers.test_clock")&&!bool(remote,"livemode")
            &&text(remote,"name").equals(owner.name())&&number(remote,"frozen_time")==pending.ready().observation().frozenTime()
            &&text(remote,"status").equals("ready")&&number(remote,"created")==clock.providerCreated()&&number(remote,"deletes_after")==clock.providerDeletesAfter());
        var customer=call(options->client.v1().customers().retrieve(intent().customerReference(),options),null);
        require(text(customer,"id").equals(intent().customerReference())&&text(customer,"object").equals("customer")&&!bool(customer,"livemode")&&text(customer,"test_clock").equals(clock.reference()));
        var subscription=call(options->client.v1().subscriptions().retrieve(admission.receipt().reference(),options),null);
        if(originalDefault==null)originalDefault=text(subscription,"default_payment_method");
        subscription(subscription,expectedDefault==null?originalDefault:expectedDefault);
        var invoice=call(options->client.v1().invoices().retrieve(pending.boundary().renewalInvoice(),options),null);
        require(text(invoice,"id").equals(pending.boundary().renewalInvoice())&&text(invoice,"object").equals("invoice")&&!bool(invoice,"livemode")
            &&text(invoice,"customer").equals(intent().customerReference())&&text(invoice,"test_clock").equals(clock.reference())
            &&Set.of("draft","open").contains(text(invoice,"status"))&&number(invoice,"amount_paid")==0&&number(invoice,"amount_due")==2000&&text(invoice,"currency").equals("eur"));
        local();
    }
    private void subscription(JsonNode remote,String method){
        require(text(remote,"id").equals(admission.receipt().reference())&&text(remote,"object").equals("subscription")&&!bool(remote,"livemode")
            &&text(remote,"customer").equals(intent().customerReference())&&text(remote,"test_clock").equals(intent().clock().reference())
            &&text(remote,"status").equals("active")&&text(remote,"latest_invoice").equals(pending.boundary().renewalInvoice())&&text(remote,"default_payment_method").equals(method));
    }
    @FunctionalInterface private interface Remote {StripeObject get(RequestOptions options)throws Exception;}
    private JsonNode call(Remote remote,String key){
        long callStart=System.nanoTime();
        long budget=Math.min(remaining(),TimeUnit.SECONDS.toNanos(2));int millis=(int)Math.max(1,Math.min(2000,TimeUnit.NANOSECONDS.toMillis(budget)));
        var builder=RequestOptions.builder().setMaxNetworkRetries(0).setConnectTimeout(millis).setReadTimeout(millis);
        if(key!=null)builder.setIdempotencyKey(key);
        RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,StripeBillingTestClockAdapter.API_VERSION);
        var options=builder.build();
        var worker=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"owned-failed-arm-sdk");thread.setDaemon(true);return thread;});
        Future<StripeObject> result=null;
        try{
            result=worker.submit(()->{require(!Thread.currentThread().isInterrupted()&&System.nanoTime()-callStart<budget);return remote.get(options);});
            long allowance=Math.min(remaining(),budget-(System.nanoTime()-callStart));require(allowance>0);var value=result.get(allowance,TimeUnit.NANOSECONDS);
            require(System.nanoTime()-callStart<budget);remaining();
            require(value!=null&&value.getLastResponse()!=null);String body=value.getLastResponse().body();
            require(body!=null&&body.length()<=65536&&body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536);
            remaining();var json=JSON.readTree(body);remaining();require(json!=null&&json.isObject());return json;
        }catch(Exception problem){if(problem instanceof InterruptedException)Thread.currentThread().interrupt();throw denied();}
        finally{if(result!=null)result.cancel(true);worker.shutdownNow();try{worker.awaitTermination(100,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    }
    private long remaining(){
        long original=budget.remainingNanos(null);var now=utc.instant();require(now.isBefore(deadline));
        long remaining=Math.min(original,Duration.between(now,deadline).toNanos());
        if(first!=null){var expires=((SubscriptionActor.Customer)first.actor()).session().expiresAt();require(now.isBefore(expires));remaining=Math.min(remaining,Duration.between(now,expires).toNanos());}
        require(remaining>0);return remaining;
    }
    private static String text(JsonNode node,String field){var value=node.get(field);require(value!=null&&value.isTextual()&&!value.textValue().isBlank());return value.textValue();}
    private static boolean bool(JsonNode node,String field){var value=node.get(field);require(value!=null&&value.isBoolean());return value.booleanValue();}
    private static long number(JsonNode node,String field){var value=node.get(field);require(value!=null&&value.isIntegralNumber()&&value.canConvertToLong());return value.longValue();}
    private static void require(boolean valid){if(!valid)throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("FAILED_RENEWAL_STOPPED");}
    @Override public String toString(){return "OwnedBillingFailedRenewalFailureArm[redacted]";}
}

record FailureArmEvidence(UUID admission,UUID boundary,UUID run,String attachedMethod,Instant acknowledgedAt){
    @Override public String toString(){return "FailureArmEvidence[redacted]";}
}
