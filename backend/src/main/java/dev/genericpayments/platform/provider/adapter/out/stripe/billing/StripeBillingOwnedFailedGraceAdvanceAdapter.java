package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.stripe.exception.StripeException;
import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import com.stripe.param.testhelpers.TestClockAdvanceParams;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Admission;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.GraceLastSecondPreparation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockAck;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockReady;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedFailedGraceAdvanceService;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Explicit original G−1 writer. It has no runtime registration or financial continuation. */
public final class StripeBillingOwnedFailedGraceAdvanceAdapter {
    private static final long CALL_NANOS=TimeUnit.SECONDS.toNanos(2),SPACE_NANOS=TimeUnit.MILLISECONDS.toNanos(250);
    private static final ObjectMapper RESPONSE_JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final OwnedFailedGraceAdvanceService service;
    private final StripeCredentialResolver credentials;private final StripeClientFactory clients;
    private final Clock utc;private final LongSupplier nanos;private final Consumer<Duration> wait;

    public StripeBillingOwnedFailedGraceAdvanceAdapter(OwnedFailedGraceAdvanceService service,
            StripeCredentialResolver credentials,StripeClientFactory clients,Clock utc){
        this(service,credentials,clients,utc,System::nanoTime,duration->{
            try{Thread.sleep(duration);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        });
    }
    StripeBillingOwnedFailedGraceAdvanceAdapter(OwnedFailedGraceAdvanceService service,
            StripeCredentialResolver credentials,StripeClientFactory clients,Clock utc,LongSupplier nanos,Consumer<Duration> wait){
        this.service=Objects.requireNonNull(service);this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);
        this.utc=Objects.requireNonNull(utc);this.nanos=Objects.requireNonNull(nanos);this.wait=Objects.requireNonNull(wait);
    }
    public GraceAdvanceReady advance(Admission admission,GraceLastSecondPreparation preparation){
        final InvocationBudget budget;
        try{budget=new InvocationBudget(utc,nanos);}catch(RuntimeException failure){throw unavailable();}
        PreparationContext original=null;ProviderOperation owned=null;AdvanceAck acknowledged=null;
        try{
            var context=service.inspect(admission,preparation,budget);original=context;
            if(!StripeBillingTestClockAdapter.API_VERSION.equals(context.admission().firstRenewalAdmission().receipt().intent().clock().owner().apiVersion()))throw unavailable();
            budget.remainingNanos(null);var resolved=credentials.resolve(context.account());budget.remainingNanos(null);
            var client=clients.create(resolved);budget.remainingNanos(null);
            // Assignment occurs only after the fresh firstclaim transaction has actually returned.
            var claim=service.claim(context,UUID.randomUUID().toString(),budget);owned=claim;budget.remainingNanos(claim);
            var reference=context.admission().ready().observation().reference();
            service.inspectClaim(context,claim,budget);
            var before=call(budget,claim,null,options->client.v1().testHelpers().testClocks().retrieve(reference,options));
            service.inspectClaim(context,claim,budget);
            var predecessor=observation(before,context);
            if(!"ready".equals(predecessor.status())||predecessor.frozenTime()!=context.admission().ready().observation().frozenTime()
                    ||predecessor.advancingTarget()!=null&&predecessor.advancingTarget()!=predecessor.frozenTime())throw unavailable();
            service.inspectClaim(context,claim,budget);
            var params=TestClockAdvanceParams.builder().setFrozenTime(context.preparation().target()).build();
            var post=call(budget,claim,context.preparation().operationKey(),options->client.v1().testHelpers().testClocks().advance(reference,params,options));
            service.inspectClaim(context,claim,budget);
            var accepted=observation(post,context);requireProgress(accepted,context);budget.remainingNanos(claim);
            var ack=service.acknowledge(context,claim,accepted,utc.instant(),budget);acknowledged=ack;
            for(int poll=0;poll<12;poll++){
                if(poll>0)space(budget,claim);
                service.inspectAck(context,ack,budget);
                var remote=call(budget,claim,null,options->client.v1().testHelpers().testClocks().retrieve(reference,options));
                service.inspectAck(context,ack,budget);
                var observed=observation(remote,context);requireProgress(observed,context);budget.remainingNanos(claim);
                if("ready".equals(observed.status())){
                    var ready=new TestClockReady(observed.reference(),observed.object(),observed.livemode(),observed.name(),observed.frozenTime(),observed.status(),observed.created(),observed.deletesAfter(),observed.advancingTarget());
                    // No post-commit expiry check: a timely committed READY is terminal even on a late return.
                    return service.recordReady(context,ack,ready,utc.instant(),budget);
                }
            }
            throw unavailable();
        }catch(RuntimeException failure){cleanup(original,owned,acknowledged,budget);throw unavailable();}
        catch(Error failure){cleanup(original,owned,acknowledged,budget);throw failure;}
    }
    private void cleanup(PreparationContext original,ProviderOperation owned,AdvanceAck ack,InvocationBudget budget){
        if(original!=null&&owned!=null)try{service.uncertain(original,owned,ack,budget);}catch(RuntimeException|Error ignored){/* Preserve original failure and consumed evidence. */}
    }
    private static void requireProgress(TestClockAck observed,PreparationContext c){
        long previous=c.admission().ready().observation().frozenTime(),target=c.preparation().target();
        if(observed.advancingTarget()!=null&&observed.advancingTarget()!=target)throw unavailable();
        if("ready".equals(observed.status())?observed.frozenTime()!=target:
                !"advancing".equals(observed.status())||observed.frozenTime()<previous||observed.frozenTime()>target)throw unavailable();
    }
    /** Normalize the unlogged original envelope before accessing SDK-coerced model properties. */
    private static TestClockAck observation(TestClock remote,PreparationContext context){
        if(remote==null||remote.getLastResponse()==null)throw unavailable();
        try{
            JsonNode raw=RESPONSE_JSON.readTree(remote.getLastResponse().body());if(raw==null||!raw.isObject())throw unavailable();
            for(String field:List.of("id","object","name","status"))if(!raw.path(field).isTextual())throw unavailable();
            for(String field:List.of("created","deletes_after","frozen_time"))if(!integral(raw.path(field)))throw unavailable();
            if(!raw.path("livemode").isBoolean()||raw.get("livemode").booleanValue())throw unavailable();
            if(raw.has("deleted")&&(!raw.get("deleted").isBoolean()||raw.get("deleted").booleanValue()))throw unavailable();
            Long target=null;var details=raw.get("status_details");
            if(details!=null&&!details.isNull()){
                if(!details.isObject()||!onlyFields(details,Set.of("advancing")))throw unavailable();
                if(details.has("advancing")){
                    var advancing=details.get("advancing");
                    if(!advancing.isObject()||!onlyFields(advancing,Set.of("target_frozen_time")))throw unavailable();
                    if(advancing.has("target_frozen_time")){if(!integral(advancing.get("target_frozen_time")))throw unavailable();target=advancing.get("target_frozen_time").longValue();}
                }
            }
            var result=new TestClockAck(raw.get("id").textValue(),raw.get("object").textValue(),false,raw.get("name").textValue(),
                raw.get("frozen_time").longValue(),raw.get("status").textValue(),raw.get("created").longValue(),raw.get("deletes_after").longValue(),target);
            if(!OwnedFailedGraceAdvance.sameClock(result,context.admission().ready().observation()))throw unavailable();
            return result;
        }catch(java.io.IOException malformed){throw unavailable();}
    }
    private static boolean integral(JsonNode node){return node!=null&&node.isIntegralNumber()&&node.canConvertToLong();}
    private static boolean onlyFields(JsonNode node,Set<String> allowed){var fields=node.fieldNames();while(fields.hasNext())if(!allowed.contains(fields.next()))return false;return true;}
    private void space(InvocationBudget budget,ProviderOperation claim){
        if(budget.remainingNanos(claim)<=SPACE_NANOS+CALL_NANOS)throw unavailable();
        wait.accept(Duration.ofNanos(SPACE_NANOS));budget.remainingNanos(claim);
    }
    private interface SdkCall<T>{T run(RequestOptions options)throws StripeException;}
    private <T>T call(InvocationBudget budget,ProviderOperation claim,String key,SdkCall<T> work){
        // Every clock is captured before scheduling. The worker can perform this call only.
        long actualStart=System.nanoTime(),injectedStart=nanos.getAsLong();var entered=utc.instant();
        long allowance=Math.min(CALL_NANOS,budget.remainingNanos(claim));var deadline=entered.plusNanos(allowance);
        LongSupplier left=()->{
            long remaining=budget.remainingNanos(claim),actualElapsed=System.nanoTime()-actualStart,injectedElapsed=nanos.getAsLong()-injectedStart;
            if(actualElapsed<0||injectedElapsed<0)throw unavailable();
            remaining=Math.min(remaining,Math.min(allowance-actualElapsed,Math.min(allowance-injectedElapsed,Duration.between(utc.instant(),deadline).toNanos())));
            if(remaining<=0)throw unavailable();return remaining;
        };
        int millis=(int)TimeUnit.NANOSECONDS.toMillis(left.getAsLong());if(millis<=0)throw unavailable();
        var builder=RequestOptions.builder().setConnectTimeout(millis).setReadTimeout(millis).setMaxNetworkRetries(0);
        if(key!=null)builder.setIdempotencyKey(key);
        RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,StripeBillingTestClockAdapter.API_VERSION);
        var options=builder.build();var pending=new FutureTask<T>(()->{left.getAsLong();return work.run(options);});
        left.getAsLong();Thread.ofVirtual().name("owned-grace-clock-sdk").start(pending);
        try{T result=pending.get(left.getAsLong(),TimeUnit.NANOSECONDS);left.getAsLong();return result;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(TimeoutException uncertain){throw unavailable();}
        catch(ExecutionException failed){if(failed.getCause() instanceof Error error)throw error;throw unavailable();}
        finally{pending.cancel(true);}
    }
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("OWNED_GRACE_CLOCK_ADVANCE_UNAVAILABLE",409,"Owned grace clock advance unavailable");}
    @Override public String toString(){return "StripeBillingOwnedFailedGraceAdvanceAdapter[redacted]";}
}
