package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedFailedGraceAdvanceInertFixture;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Actual service plus pinned SDK mapper and explicit inert transport. No runtime or sockets. */
class StripeBillingOwnedFailedGraceAdvanceAdapterTest {
    @Test void originalReadyOneExactPostCommittedAckAndFreshGetProduceIndependentReady(){
        var f=new Fixture();f.queue();var before=f.local.financialState();var ready=f.advance();
        assertThat(ready).isEqualTo(f.local.savedReady());assertThat(ready.ack()).isEqualTo(f.local.ack());
        assertThat(ready.observation().frozenTime()).isEqualTo(f.target());assertThat(f.local.row().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(f.sdk.http.calls.stream().map(r->r.method().name()+" "+r.url().getPath()).toList()).containsExactly(
            "GET /v1/test_helpers/test_clocks/clock_owned","POST /v1/test_helpers/test_clocks/clock_owned/advance","GET /v1/test_helpers/test_clocks/clock_owned");
        var post=f.posts().getFirst();assertThat(post.params()).containsExactlyInAnyOrderEntriesOf(Map.of("frozen_time",f.target()));
        assertThat(post.options().getIdempotencyKey()).isEqualTo(f.local.context.preparation().operationKey());
        f.sdk.http.calls.forEach(f::options);f.gets().forEach(r->assertThat(r.options().getIdempotencyKey()).isNull());
        assertThat(f.local.receiptCalls()).containsSubsequence("firstclaim","claim","claim","ack","requireAck","requireAck","ready");
        var budget=f.local.originalBudget();assertThat(budget).isNotSameAs(f.local.budget);assertThat(budget.enteredAt()).isEqualTo(f.entry);assertThat(budget.deadlineAt()).isEqualTo(f.entry.plusSeconds(30));
        assertThat(ready.ack().invocation()).isEqualTo(budget.invocation());assertThat(f.local.financialState()).isEqualTo(before);assertThat(f.local.stops()).isZero();assertThat(f.sdk.http.replies).isEmpty();
        assertThat(ready.toString()+ready.ack()+f.adapter()).doesNotContain("sk_test","clock_owned",f.local.context.preparation().id().toString());
        var saved=f.local.savedReady();refused(f::advance);assertThat(f.posts()).hasSize(1);assertThat(f.local.savedReady()).isEqualTo(saved);assertThat(f.local.stops()).isZero();
    }
    @Test void readyPostIsOnlyAckAndAlwaysRequiresFreshIndependentGet(){
        var f=new Fixture();f.sdk.http.reply(f.clock(f.previous(),"ready"));f.sdk.http.reply(f.clock(f.target(),"ready"));f.sdk.http.disconnect();
        refused(f::advance);f.onlyAck();assertThat(f.gets()).hasSize(2);assertThat(f.posts()).hasSize(1);
    }
    @Test void advancingPollsAreFreshSpacedFiniteAndUseAckAuthority(){
        var f=new Fixture();f.sdk.http.reply(f.clock(f.previous(),"ready"));f.sdk.http.reply(f.clock(f.previous(),"advancing"));
        for(int i=0;i<12;i++)f.sdk.http.reply(f.clock(f.previous(),"advancing"));
        refused(f::advance);f.onlyAck();assertThat(f.gets()).hasSize(13);assertThat(f.posts()).hasSize(1);assertThat(f.waits).hasSize(11).allSatisfy(d->assertThat(d).isGreaterThanOrEqualTo(Duration.ofMillis(250)));
        assertThat(f.local.receiptCalls().stream().filter("requireAck"::equals).count()).isGreaterThanOrEqualTo(12);
    }
    @Test void subsequentReadyPollIsAcceptedWithoutRetryingThePost(){
        var f=new Fixture();f.sdk.http.reply(f.clock(f.previous(),"ready"));f.sdk.http.reply(f.clock(f.previous(),"advancing"));f.sdk.http.reply(f.clock(f.previous(),"advancing"));f.sdk.http.reply(f.clock(f.target(),"ready"));
        assertThat(f.advance()).isEqualTo(f.local.savedReady());assertThat(f.posts()).hasSize(1);assertThat(f.waits).containsExactly(Duration.ofMillis(250));assertThat(f.local.stops()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"id","object","name","status","livemode","created","deletes_after","frozen_time"})
    void everyRequiredWireFieldMustBePresentWithNoPrimitiveDefault(String field){
        for(String phase:List.of("get","post","poll")){var f=new Fixture();f.queueBad(phase,remove(f.phaseClock(phase),field));refused(f::advance);f.consumed(phase);}
    }
    @ParameterizedTest @ValueSource(strings={"id","object","name","status","livemode","created","deletes_after","frozen_time"})
    void duplicateFieldsAreRejectedBeforeSdkGettersHideThem(String field){
        for(String phase:List.of("get","post","poll")){var f=new Fixture();String body=f.phaseClock(phase);f.queueBad(phase,body.substring(0,body.length()-1)+",\""+field+"\":"+wireValue(body,field)+"}");refused(f::advance);f.consumed(phase);}
    }
    @ParameterizedTest @ValueSource(strings={"created","deletes_after","frozen_time"})
    void allWireLongsRejectNullBooleanStringFractionOverflowAndContainers(String field){
        for(String phase:List.of("get","post","poll"))for(String literal:List.of("null","true","\"1\"","1.0","9223372036854775808","[]","{}")){var f=new Fixture();f.queueBad(phase,replace(f.phaseClock(phase),field,literal));refused(f::advance);f.consumed(phase);}
    }
    @Test void supportedAdvancingDetailsMustHaveOnlyTheExactIntegralTargetShape(){
        for(String phase:List.of("get","post","poll"))for(String details:List.of("{\"advancing\":{\"extra\":1}}","{\"advancing\":{\"target_frozen_time\":null}}","{\"advancing\":{},\"internal_failure\":{}}")){var f=new Fixture();f.queueBad(phase,replace(f.phaseClock(phase),"status_details",details));refused(f::advance);f.consumed(phase);}
        var f=new Fixture();for(String phase:List.of("get","post","poll"))f.sdk.http.reply(replace(f.phaseClock(phase),"status_details","{\"advancing\":{\"target_frozen_time\":"+(phase.equals("get")?f.previous():f.target())+"}}"));assertThat(f.advance()).isNotNull();assertThat(f.local.stops()).isZero();assertThat(f.posts()).hasSize(1);
    }
    @Test void preflightReadyWithFutureAdvancingDetailsCannotAuthorizePost(){
        var f=new Fixture();var financial=f.local.financialState();
        f.sdk.http.reply(replace(f.phaseClock("get"),"status_details","{\"advancing\":{\"target_frozen_time\":"+f.target()+"}}"));
        f.sdk.http.reply(f.phaseClock("post"));f.sdk.http.reply(f.phaseClock("poll"));
        refused(f::advance);f.onlyUnknown();assertThat(f.gets()).hasSize(1);assertThat(f.posts()).isEmpty();
        assertThat(f.local.receiptCalls().stream().filter("cleanup"::equals).count()).isEqualTo(1);assertThat(f.local.financialState()).isEqualTo(financial);
    }
    @ParameterizedTest @ValueSource(strings={"id-null","object-number","name-boolean","status-array","live-string","created-string","created-fraction","created-overflow","deleted-null","deleted-true","deleted-string","details-array","details-string","details-unknown","details-internal","advancing-null","advancing-array","target-string","target-fraction","target-overflow","target-duplicate","null","invalid-json"})
    void literalMalformedWireCannotBecomePositiveEvidenceAtAnyPhase(String bad){
        for(String phase:List.of("get","post","poll")){var f=new Fixture();String body=f.phaseClock(phase);String malformed=switch(bad){
            case "id-null"->replace(body,"id","null");case "object-number"->replace(body,"object","7");case "name-boolean"->replace(body,"name","false");case "status-array"->replace(body,"status","[]");
            case "live-string"->replace(body,"livemode","\"false\"");case "created-string"->replace(body,"created","\"1\"");case "created-fraction"->replace(body,"created","1.0");case "created-overflow"->replace(body,"created","9223372036854775808");
            case "deleted-null"->add(body,"deleted","null");case "deleted-true"->add(body,"deleted","true");case "deleted-string"->add(body,"deleted","\"false\"");
            case "details-array"->replace(body,"status_details","[]");case "details-string"->replace(body,"status_details","\"ready\"");case "details-unknown"->replace(body,"status_details","{\"unexpected\":{}}");case "details-internal"->replace(body,"status_details","{\"internal_failure\":{}}");
            case "advancing-null"->replace(body,"status_details","{\"advancing\":null}");case "advancing-array"->replace(body,"status_details","{\"advancing\":[]}");
            case "target-string"->replace(body,"status_details","{\"advancing\":{\"target_frozen_time\":\""+f.target()+"\"}}");case "target-fraction"->replace(body,"status_details","{\"advancing\":{\"target_frozen_time\":1.0}}");
            case "target-overflow"->replace(body,"status_details","{\"advancing\":{\"target_frozen_time\":9223372036854775808}}");case "target-duplicate"->body.replace("\"status_details\":{}","\"status_details\":{\"advancing\":{\"target_frozen_time\":"+f.target()+",\"target_frozen_time\":"+f.target()+"}}");
            case "null"->"null";default->"{PRIVATE_PROVIDER_DETAIL";};if(bad.equals("target-duplicate"))assertThat(malformed.split("\"target_frozen_time\":",-1)).hasSize(3);f.queueBad(phase,malformed);refused(f::advance);f.consumed(phase);}
    }
    @ParameterizedTest @ValueSource(strings={"id","object","name","live","created","deletes","frozen","target","status","internal_failure","ready-intermediate"})
    void identityStatusAndTargetMismatchesRefuseAtEachSdkPhase(String bad){
        for(String phase:List.of("get","post","poll")){var f=new Fixture();String body=f.phaseClock(phase);String changed=switch(bad){
            case "id"->replace(body,"id","\"clock_foreign\"");case "object"->replace(body,"object","\"customer\"");case "name"->replace(body,"name","\"foreign\"");case "live"->replace(body,"livemode","true");case "created"->replace(body,"created","1");case "deletes"->replace(body,"deletes_after","2");case "frozen"->replace(body,"frozen_time",Long.toString(f.target()+1));
            case "target"->replace(body,"status_details","{\"advancing\":{\"target_frozen_time\":"+(f.target()+1)+"}}");case "status"->replace(body,"status","\"unknown\"");case "internal_failure"->replace(body,"status","\"internal_failure\"");default->replace(replace(body,"status","\"ready\""),"frozen_time",Long.toString(f.previous()+1));};
            f.queueBad(phase,changed);refused(f::advance);f.consumed(phase);}
    }
    @Test void absentNullAndEmptySupportedDetailsAndDeletedFalseAreAccepted(){
        for(String details:List.of("absent","null","{}")){var f=new Fixture();for(String phase:List.of("get","post","poll")){String body=f.phaseClock(phase);body=details.equals("absent")?remove(body,"status_details"):replace(body,"status_details",details);f.sdk.http.reply(add(body,"deleted","false"));}assertThat(f.advance()).isNotNull();assertThat(f.local.stops()).isZero();}
    }
    @ParameterizedTest @ValueSource(strings={"sample","mode","owner","debt","preparation","stop"})
    void currentAuthorityMustSurviveEveryCallerPhase(String drift){
        for(String phase:List.of("preflight","claim","get","post","ack","poll")){var f=new Fixture();f.queue();
            if(phase.equals("preflight"))f.local.drift(drift);
            if(phase.equals("claim"))f.local.afterCommit(()->{if(f.local.row()!=null&&f.local.ack()==null)f.local.drift(drift);});
            if(phase.equals("get"))f.afterGet=()->{if(f.gets().size()==1)f.local.drift(drift);};
            if(phase.equals("post"))f.afterPost=()->f.local.drift(drift);
            if(phase.equals("ack"))f.local.afterCommit(()->{if(f.local.ack()!=null)f.local.drift(drift);});
            if(phase.equals("poll"))f.afterGet=()->{if(f.gets().size()==2)f.local.drift(drift);};
            refused(f::advance);assertThat(f.local.savedReady()).isNull();if(phase.equals("preflight")){assertThat(f.clientsCreated).hasValue(0);assertThat(f.local.row()).isNull();assertThat(f.local.stops()).isZero();}
            else if(List.of("ack","poll").contains(phase))f.onlyAck();else f.onlyUnknown();assertThat(f.posts()).hasSize(List.of("post","ack","poll").contains(phase)?1:0);
        }
    }
    @Test void everyExistingStateAndPriorReadyRefusesWithoutAdoptionOrLoserStop(){
        for(var status:ProviderOperation.Status.values()){var f=new Fixture();f.local.existingStatus(status);var row=f.local.row();f.queue();refused(f::advance);assertThat(f.local.row()).isEqualTo(row);assertThat(f.posts()).isEmpty();assertThat(f.local.stops()).isZero();}
        var f=new Fixture();f.queue();f.advance();var saved=f.local.savedReady();refused(f::advance);assertThat(f.local.savedReady()).isEqualTo(saved);assertThat(f.posts()).hasSize(1);assertThat(f.local.stops()).isZero();
    }
    @Test void credentialAndClientFailureBeforeReturnedClaimCannotStopAnything(){
        var f=new Fixture("alias");refused(f::advance);assertThat(f.clientsCreated).hasValue(0);assertThat(f.local.row()).isNull();assertThat(f.local.stops()).isZero();
        var g=new Fixture();g.beforeClient=()->{throw new IllegalStateException("PRIVATE_PROVIDER_DETAIL sk_test_secret");};refused(g::advance);assertThat(g.local.row()).isNull();assertThat(g.local.stops()).isZero();
        var h=new Fixture();var failure=new AssertionError("original client error");h.beforeClient=()->{throw failure;};assertThatThrownBy(h::advance).isSameAs(failure);assertThat(h.local.row()).isNull();assertThat(h.local.stops()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"auth","client","claim","get","post","ack","poll","ready"})
    void everyCallerAndSdkPhaseConsumesTheSameOriginalBudget(String phase){
        var f=new Fixture();f.queue();Runnable expire=()->f.local.elapsed(30_000_000_000L);
        switch(phase){case "auth"->f.local.afterLock(expire);case "client"->f.beforeClient=expire;case "claim"->f.local.afterEffect(()->{if(f.local.ack()==null)expire.run();});case "get"->f.afterGet=()->{if(f.gets().size()==1)expire.run();};case "post"->f.afterPost=expire;case "ack"->f.local.afterEffect(()->{if(f.local.ack()!=null)expire.run();});case "poll"->f.afterGet=()->{if(f.gets().size()==2)expire.run();};case "ready"->f.local.afterEffect(()->{if(f.local.savedReady()!=null)expire.run();});default->throw new AssertionError(phase);}
        refused(f::advance);assertThat(f.local.savedReady()).isNull();if(List.of("auth","client","claim").contains(phase)){assertThat(f.local.row()).isNull();assertThat(f.local.stops()).isZero();assertThat(f.posts()).isEmpty();}else if(List.of("poll","ready").contains(phase))f.onlyAck();else f.onlyUnknown();
    }
    @ParameterizedTest @ValueSource(strings={"utc","nanos"})
    void perCallExclusiveTwoSecondDeadlineIsCheckedAfterSuccessfulSdkReturn(String clock){
        for(String phase:List.of("get","post","poll")){var f=new Fixture();f.queue();Runnable expire=()->{if(clock.equals("utc"))f.local.at(f.local.now().plusSeconds(2));else f.local.elapsed(f.local.nanos()+2_000_000_000L);};if(phase.equals("post"))f.afterPost=expire;else f.afterGet=()->{if(f.gets().size()==(phase.equals("get")?1:2))expire.run();};refused(f::advance);f.consumed(phase);}
    }
    @ParameterizedTest @ValueSource(strings={"utc","nanos"})
    void workerCannotEnterSdkIfSchedulingConsumedTheCallAllowance(String clock){
        var f=new Fixture();f.queue();var caller=Thread.currentThread();if(clock.equals("nanos"))f.invocationNanos=()->Thread.currentThread()==caller?0:2_000_000_000L;
        else f.invocationClock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Thread.currentThread()==caller?f.local.now():f.local.now().plusSeconds(2);}};
        refused(f::advance);assertThat(f.sdk.http.calls).isEmpty();f.onlyUnknown();
    }
    @Test void waitCannotBeginWithoutBothSpacingAndNextCallAllowance(){
        var f=new Fixture();f.queue();f.sdk.http.replies.removeLast();f.sdk.http.reply(f.clock(f.previous(),"advancing"));f.afterGet=()->{if(f.gets().size()==2)f.local.elapsed(28_000_000_000L);};refused(f::advance);f.onlyAck();assertThat(f.waits).isEmpty();assertThat(f.gets()).hasSize(2);
    }
    @Test void workerErrorIsRethrownUnchangedAfterExactlyOneOriginalCleanup(){
        for(String phase:List.of("get","post","poll")){var f=new Fixture();f.queue();var original=new AssertionError("original SDK error");f.beforeRequest=r->{if(phase.equals("post")?r.method().name().equals("POST"):r.method().name().equals("GET")&&f.gets().size()==(phase.equals("get")?0:1))throw original;};assertThatThrownBy(f::advance).isSameAs(original);if(phase.equals("poll"))f.onlyAck();else f.onlyUnknown();}
    }
    @Test void callerInterruptionIsPreservedAndNeverSchedulesAnotherSdkCall(){
        var f=new Fixture();f.queue();Thread.currentThread().interrupt();try{refused(f::advance);assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(f.local.row()).isNull();assertThat(f.sdk.http.calls).isEmpty();assertThat(f.local.stops()).isZero();}finally{Thread.interrupted();}
        var g=new Fixture();g.queue();g.afterPost=()->g.caller.interrupt();try{refused(g::advance);assertThat(Thread.currentThread().isInterrupted()).isTrue();g.onlyUnknown();assertThat(g.posts()).hasSize(1);assertThat(g.gets()).hasSize(1);}finally{Thread.interrupted();}
    }
    @Test void blockedLateSdkWorkerCannotWriteOrStopAfterCallerHasConsumedItsClaim()throws Exception{
        var f=new Fixture();f.queue();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var returned=new CountDownLatch(1);
        f.beforeRequest=r->{if(r.method().name().equals("POST")){entered.countDown();boolean interrupted=false;while(true){try{release.await();break;}catch(InterruptedException ignored){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();}};
        f.afterPost=returned::countDown;
        try{long started=System.nanoTime();refused(f::advance);assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(5));assertThat(entered.getCount()).isZero();f.onlyUnknown();var saved=f.local.row();var calls=f.local.receiptCalls();release.countDown();assertThat(returned.await(2,TimeUnit.SECONDS)).isTrue();assertThat(f.local.row()).isEqualTo(saved);assertThat(f.local.receiptCalls()).isEqualTo(calls);assertThat(f.local.stops()).isEqualTo(1);assertThat(f.posts()).hasSize(1);}finally{release.countDown();}
    }
    @Test void storageFailureRollsBackOnlyTheTentativePositiveReceipt(){
        var f=new Fixture();f.queue();f.local.failAck();refused(f::advance);f.onlyUnknown();assertThat(f.posts()).hasSize(1);
        var g=new Fixture();g.queue();g.local.failReady();refused(g::advance);g.onlyAck();assertThat(g.posts()).hasSize(1);
    }
    @Test void ambiguousCommittedAckCleansOnlyOriginalAndCommittedReadyRemainsTerminal(){
        for(String phase:List.of("ack","ready")){var f=new Fixture();f.queue();f.local.afterCommit(()->{if(phase.equals("ack")?f.local.ack()!=null:f.local.savedReady()!=null)throw new IllegalStateException("ambiguous committed return");});refused(f::advance);assertThat(f.local.ack()).isNotNull();assertThat(f.local.row().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(f.local.stops()).isEqualTo(phase.equals("ack")?1:0);assertThat(f.local.savedReady()!=null).isEqualTo(phase.equals("ready"));assertThat(f.posts()).hasSize(1);}
    }
    @Test void committedReadyLateSuccessfulReturnIsPreservedWithoutPostCommitExpiryCheck(){
        var f=new Fixture();f.queue();f.local.afterCommit(()->{if(f.local.savedReady()!=null){f.local.elapsed(31_000_000_000L);f.local.at(f.entry.plusSeconds(31));}});assertThat(f.advance()).isEqualTo(f.local.savedReady());assertThat(f.local.stops()).isZero();assertThat(f.posts()).hasSize(1);
    }
    @Test void postAckUtcRollbackUsesIdentityCleanupRatherThanFreshPositiveAuthority(){
        var f=new Fixture();f.queue();f.local.afterCommit(()->{if(f.local.ack()!=null)f.local.at(f.entry.minusSeconds(1));});refused(f::advance);f.onlyAck();assertThat(f.gets()).hasSize(1);
    }
    static void refused(Runnable call){assertThatThrownBy(call::run).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned grace clock advance unavailable").hasNoCause().satisfies(error->{assertThat(((PaymentApplicationException)error).code()).isEqualTo("OWNED_GRACE_CLOCK_ADVANCE_UNAVAILABLE");assertThat(error.getSuppressed()).isEmpty();assertThat(error.toString()).doesNotContain("PRIVATE_PROVIDER_DETAIL","sk_test","clock_owned");});}
    static String replace(String body,String field,String raw){var root=com.google.gson.JsonParser.parseString(body).getAsJsonObject();root.add(field,com.google.gson.JsonParser.parseString(raw));return root.toString();}
    static String remove(String body,String field){var root=com.google.gson.JsonParser.parseString(body).getAsJsonObject();root.remove(field);return root.toString();}
    static String add(String body,String field,String raw){return replace(body,field,raw);}
    static String wireValue(String body,String field){return com.google.gson.JsonParser.parseString(body).getAsJsonObject().get(field).toString();}
    static final class Fixture {
        final OwnedFailedGraceAdvanceInertFixture local;final StripeBillingInertSdkFixture sdk=new StripeBillingInertSdkFixture();final AtomicInteger clientsCreated=new AtomicInteger();final List<Duration> waits=new ArrayList<>();final Thread caller=Thread.currentThread();final Instant entry;
        Clock invocationClock;LongSupplier invocationNanos;Runnable beforeClient=()->{},afterGet=()->{},afterPost=()->{};Consumer<StripeRequest> beforeRequest=r->{};
        Fixture(){this("stripe-sandbox-default");}
        Fixture(String alias){local=new OwnedFailedGraceAdvanceInertFixture(123456789,alias);local.acceptAdapterBudget();entry=local.now();invocationClock=local.clock();invocationNanos=local::nanos;}
        final HttpClient transport=new HttpClient(){public StripeResponse request(StripeRequest request)throws StripeException{
            assertThat(Thread.currentThread()).isNotSameAs(caller);assertThat(local.active()).isFalse();assertThat(local.row()).isNotNull();assertThat(local.originalBudget()).isNotNull();
            if(request.method().name().equals("POST"))assertThat(local.row().status()).isEqualTo(ProviderOperation.Status.DISPATCHING);else if(!sdk.http.calls.isEmpty())assertThat(local.ack()).isNotNull();beforeRequest.accept(request);
            var response=sdk.http.request(request);if(request.method().name().equals("POST"))afterPost.run();else afterGet.run();return response;
        }};
        final StripeClientFactory clients=new StripeClientFactory(){public StripeClient create(StripeCredentials credentials){assertThat(Thread.currentThread()).isSameAs(caller);assertThat(local.active()).isFalse();clientsCreated.incrementAndGet();beforeClient.run();return StripeClient.builder().setApiKey("sk_test_INERT").setMaxNetworkRetries(0).setHttpClient(transport).build();}};
        StripeBillingOwnedFailedGraceAdvanceAdapter adapter(){return new StripeBillingOwnedFailedGraceAdvanceAdapter(local.service,sdk.credentials,clients,invocationClock,invocationNanos,d->{waits.add(d);local.elapsed(local.nanos()+d.toNanos());local.at(local.now().plus(d));});}
        GraceAdvanceReady advance(){return adapter().advance(local.context.admission(),local.context.preparation());}
        long previous(){return local.context.admission().ready().observation().frozenTime();}long target(){return local.context.preparation().target();}
        String clock(long frozen,String status){var c=local.context.admission().ready().observation();return "{\"id\":\""+c.reference()+"\",\"object\":\""+c.object()+"\",\"livemode\":false,\"name\":\""+c.name()+"\",\"created\":"+c.created()+",\"deletes_after\":"+c.deletesAfter()+",\"frozen_time\":"+frozen+",\"status\":\""+status+"\",\"status_details\":{}}";}
        String phaseClock(String phase){return phase.equals("get")?clock(previous(),"ready"):phase.equals("post")?clock(previous(),"advancing"):clock(target(),"ready");}
        void queue(){for(String phase:List.of("get","post","poll"))sdk.http.reply(phaseClock(phase));}
        void queueBad(String phase,String body){for(String current:List.of("get","post","poll")){sdk.http.reply(current.equals(phase)?body:phaseClock(current));if(current.equals(phase))break;}}
        List<StripeRequest> gets(){return sdk.http.calls.stream().filter(r->r.method().name().equals("GET")).toList();}List<StripeRequest> posts(){return sdk.http.calls.stream().filter(r->r.method().name().equals("POST")).toList();}
        void options(StripeRequest r){assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.options().getConnectTimeout()).isBetween(1,2000);assertThat(r.options().getReadTimeout()).isBetween(1,2000);assertThat(r.options().getStripeAccount()).isNull();assertThat(r.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");}
        void consumed(String phase){if(phase.equals("poll"))onlyAck();else onlyUnknown();assertThat(posts()).hasSize(phase.equals("get")?0:1);}
        void onlyUnknown(){assertThat(local.row()).isNotNull();assertThat(local.row().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(local.ack()).isNull();assertThat(local.savedReady()).isNull();assertThat(local.stops()).isEqualTo(1);}
        void onlyAck(){assertThat(local.row().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(local.ack()).isNotNull();assertThat(local.savedReady()).isNull();assertThat(local.stops()).isEqualTo(1);}
    }
}
