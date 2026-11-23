package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import com.stripe.param.testhelpers.TestClockAdvanceParams;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockAck;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockReady;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedGraceAdvanceAdapter.Checkpoint;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Clock transport for the original and boundary producers. All hooks run on the caller. */
final class OwnedGraceClockExchange {
    private static final long CALL_NANOS=TimeUnit.SECONDS.toNanos(2),SPACE_NANOS=TimeUnit.MILLISECONDS.toNanos(250);
    private static final int MAX_POLLS=12;
    private static final ObjectMapper RESPONSE_JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final StripeClient client;
    private final TestClockReady previous;
    private final long target;
    private final String key;
    private final InvocationBudget budget;
    private final ProviderOperation claim;
    private final Clock utc;
    private final LongSupplier nanos;
    private final Consumer<Duration> wait;
    private final Consumer<Checkpoint> checkpoint;

    OwnedGraceClockExchange(StripeClient client,TestClockReady previous,long target,String key,InvocationBudget budget,
            ProviderOperation claim,Clock utc,LongSupplier nanos,Consumer<Duration> wait){
        this(client,previous,target,key,budget,claim,utc,nanos,wait,ignored->{});
    }
    OwnedGraceClockExchange(StripeClient client,TestClockReady previous,long target,String key,InvocationBudget budget,
            ProviderOperation claim,Clock utc,LongSupplier nanos,Consumer<Duration> wait,Consumer<Checkpoint> checkpoint){
        this.client=client;this.previous=previous;this.target=target;this.key=key;this.budget=budget;
        this.claim=claim;this.utc=utc;this.nanos=nanos;this.wait=wait;this.checkpoint=checkpoint;
    }

    <A>A acknowledge(Runnable inspectClaim,BiFunction<TestClockAck,Instant,A> acknowledge){
        var reference=previous.reference();
        checkpoint.accept(Checkpoint.PRE_READ);
        inspectClaim.run();
        var before=call(budget,claim,null,options->client.v1().testHelpers().testClocks().retrieve(reference,options));
        checkpoint.accept(Checkpoint.PRE_VALIDATE);
        inspectClaim.run();
        var predecessor=observation(before,previous);
        if(!"ready".equals(predecessor.status())||predecessor.frozenTime()!=previous.frozenTime()
                ||predecessor.advancingTarget()!=null&&predecessor.advancingTarget()!=predecessor.frozenTime())throw unavailable();
        checkpoint.accept(Checkpoint.POST);
        inspectClaim.run();
        var params=TestClockAdvanceParams.builder().setFrozenTime(target).build();
        var post=call(budget,claim,key,options->client.v1().testHelpers().testClocks().advance(reference,params,options));
        checkpoint.accept(Checkpoint.POST_VALIDATE);
        inspectClaim.run();
        var accepted=observation(post,previous);requireProgress(accepted,previous.frozenTime(),target);budget.remainingNanos(claim);
        checkpoint.accept(Checkpoint.ACK_STORE);
        return acknowledge.apply(accepted,utc.instant());
    }

    <R>R recordReady(Runnable inspectAcknowledged,BiFunction<TestClockReady,Instant,R> recordReady){
        for(int poll=0;poll<MAX_POLLS;poll++){
            if(poll>0){checkpoint.accept(Checkpoint.POLL_WAIT);space(poll);}
            checkpoint.accept(Checkpoint.POLL_READ);
            inspectAcknowledged.run();
            var remote=call(budget,claim,null,options->client.v1().testHelpers().testClocks().retrieve(previous.reference(),options));
            checkpoint.accept(Checkpoint.POLL_VALIDATE);
            inspectAcknowledged.run();
            var observed=observation(remote,previous);requireProgress(observed,previous.frozenTime(),target);budget.remainingNanos(claim);
            if("ready".equals(observed.status())){
                var ready=new TestClockReady(observed.reference(),observed.object(),observed.livemode(),observed.name(),observed.frozenTime(),
                    observed.status(),observed.created(),observed.deletesAfter(),observed.advancingTarget());
                checkpoint.accept(Checkpoint.READY_STORE);
                return recordReady.apply(ready,utc.instant());
            }
        }
        checkpoint.accept(Checkpoint.POLL_EXHAUSTED);
        throw unavailable();
    }
    private static void requireProgress(TestClockAck observed,long previous,long target){
        if(observed.advancingTarget()!=null&&observed.advancingTarget()!=target)throw unavailable();
        if("ready".equals(observed.status())?observed.frozenTime()!=target:
                !"advancing".equals(observed.status())||observed.frozenTime()<previous||observed.frozenTime()>target)throw unavailable();
    }
    /** Normalize the unlogged original envelope before accessing SDK-coerced model properties. */
    private static TestClockAck observation(TestClock remote,TestClockReady previous){
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
            if(!OwnedFailedGraceAdvance.sameClock(result,previous))throw unavailable();
            return result;
        }catch(java.io.IOException malformed){throw unavailable();}
    }
    private static boolean integral(JsonNode node){return node!=null&&node.isIntegralNumber()&&node.canConvertToLong();}
    private static boolean onlyFields(JsonNode node,Set<String> allowed){var fields=node.fieldNames();while(fields.hasNext())if(!allowed.contains(fields.next()))return false;return true;}
    private void space(int poll){
        long remaining=budget.remainingNanos(claim);
        if(remaining<=SPACE_NANOS+CALL_NANOS)throw unavailable();
        // Keep the first follow-up fast and distribute later reads within the original budget.
        long delay=poll==1?SPACE_NANOS:Math.max(SPACE_NANOS,(remaining-CALL_NANOS)/(MAX_POLLS-poll));
        wait.accept(Duration.ofNanos(delay));
        budget.remainingNanos(claim);
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
    private static IllegalStateException unavailable(){return new IllegalStateException("Owned grace clock exchange unavailable");}
}

