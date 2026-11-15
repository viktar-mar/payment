package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.ConfirmationAck;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.ConfirmReply;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Only handwritten operations, memory HTTP exchanges and admission; never opens a socket/context. */
class OwnedBillingFailedRenewalRuntimeTest {
    private static final String TYPE="dev.genericpayments.acceptance.runtime.OwnedBillingFailedRenewalRuntime";
    private static final String PREFIX="PAYMENT_STRIPE_BILLING_FAILED_RENEWAL_";
    private static final String KEY="q".repeat(43), SHA="a".repeat(40);
    private static final List<String> ROUTES=List.of("boundary","arm-decline","failed","grace-last-second","grace-at","grace-after",
        "method/prepare","method/select","method/confirm","method/apply","recovery/confirm","recovery/observe");

    static Map<String,String> environment(){
        var env=new HashMap<String,String>();env.put("PAYMENT_STRIPE_BILLING_FAILED_RENEWAL","true");env.put("PAYMENT_STRIPE_SANDBOX_ENABLED","true");
        env.put("PAYMENT_RUNTIME_SOURCE_SHA",SHA);env.put(PREFIX+"RUN_ID","cd2116f9-f847-4dc8-85fa-7df63ca51451");
        env.put(PREFIX+"SOURCE_SHA",SHA);env.put(PREFIX+"FROZEN_TIME","1700000000");env.put(PREFIX+"CONTROL_KEY",KEY);env.put(PREFIX+"CONTROL_PORT","8099");return env;
    }
    // Removing the new admission boundary must fail before any startup factory is acquired.
    @Test void onlyAnExactDedicatedAdmissionCanReachTheStartupFactory() throws Exception {
        var calls=new AtomicInteger();var env=environment();
        var admission=admitted(new String[0],env,a->{calls.incrementAndGet();return a;});
        assertThat(admission.run().toString()).isEqualTo("cd2116f9-f847-4dc8-85fa-7df63ca51451");
        assertThat(admission.source()).isEqualTo(SHA);assertThat(admission.frozen()).isEqualTo(1700000000L);
        assertThat(admission.port()).isEqualTo(8099);assertThat(admission.bind()).isEqualTo("127.0.0.1");
        assertThat(env).doesNotContainKey("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT");
        for(String required:List.of("PAYMENT_STRIPE_BILLING_FAILED_RENEWAL","PAYMENT_STRIPE_SANDBOX_ENABLED","PAYMENT_RUNTIME_SOURCE_SHA",PREFIX+"RUN_ID",PREFIX+"SOURCE_SHA",PREFIX+"FROZEN_TIME",PREFIX+"CONTROL_KEY",PREFIX+"CONTROL_PORT")){
            var invalid=new HashMap<>(env);invalid.remove(required);denied(invalid,calls);
        }
        assertThatThrownBy(()->admitted(new String[]{"--run"},env,a->{calls.incrementAndGet();return a;})).hasMessage("FAILED_RENEWAL_ADMISSION_DENIED");
        assertThat(calls).hasValue(1);
    }
    @Test void mixedModesAndDiagnosticsAreDeniedBeforeAnyDependency() throws Exception {
        var calls=new AtomicInteger();
        for(String flag:List.of("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT","PAYMENT_STRIPE_BILLING_FIRST_RENEWAL","PAYMENT_STRIPE_BILLING_ACCEPTANCE","PAYMENT_STRIPE_ACCEPTANCE","PAYMENT_STRIPE_NATIVE_ACCEPTANCE","PAYMENT_STAGE4_TEST_RUNTIME",
            "PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS","PAYMENT_STRIPE_SANDBOX_FULL_RUN","PAYMENT_STRIPE_SANDBOX_OWNED_RUN","PAYMENT_STRIPE_SANDBOX_REMAINING_ONLY","PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC","PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC","PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY","PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT","PAYMENT_STRIPE_SANDBOX_MANUAL_3DS","PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND","PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER")){
            for(String value:List.of("true","1","yes")){var env=environment();env.put(flag,value);denied(env,calls);}
        }
        for(String flag:List.of("DEBUG","NODE_DEBUG","PWDEBUG","PW_TRACE")){var env=environment();env.put(flag,"1");denied(env,calls);}
        var inert=environment();inert.put("DEBUG","");inert.put("NODE_DEBUG","");inert.put("PWDEBUG","0");inert.put("PW_TRACE","off");
        admitted(new String[0],inert,a->{calls.incrementAndGet();return a;});assertThat(calls).hasValue(1);
    }
    @Test void sourceRunClockCapabilityPortAndBindAreValidatedBeforeStartup() throws Exception {
        var calls=new AtomicInteger();
        for(var pair:List.of(new String[]{"PAYMENT_RUNTIME_SOURCE_SHA","b".repeat(40)},new String[]{PREFIX+"SOURCE_SHA","b".repeat(40)},
            new String[]{PREFIX+"RUN_ID","not-a-uuid"},new String[]{PREFIX+"RUN_ID","CD2116F9-F847-4DC8-85FA-7DF63CA51451"},
            new String[]{PREFIX+"FROZEN_TIME","0"},new String[]{PREFIX+"FROZEN_TIME","9007199254740992"},new String[]{PREFIX+"CONTROL_KEY","short"},
            new String[]{PREFIX+"CONTROL_PORT","1023"},new String[]{PREFIX+"CONTROL_PORT","65536"},new String[]{PREFIX+"CONTROL_PORT","+8099"},
            new String[]{PREFIX+"CONTROL_PORT","08099"},new String[]{PREFIX+"CONTROL_BIND","localhost"})){
            var env=environment();env.put(pair[0],pair[1]);denied(env,calls);
        }
        assertThat(calls).hasValue(0);
    }
    @Test void legacyJavaGatesRejectTheDedicatedMode() {
        var first=new HashMap<String,String>();environment().forEach((k,v)->first.put(k.replace("FAILED_RENEWAL","FIRST_PAYMENT"),v));
        first.put("PAYMENT_STRIPE_BILLING_FAILED_RENEWAL","true");
        assertThatThrownBy(()->OwnedBillingFirstPaymentRuntime.admit(first,SHA)).hasMessage("INITIAL_PAYMENT_ADMISSION_DENIED");
        var renewal=new HashMap<String,String>();environment().forEach((k,v)->renewal.put(k.replace("FAILED_RENEWAL","FIRST_RENEWAL"),v));
        renewal.put("PAYMENT_STRIPE_BILLING_FAILED_RENEWAL","true");
        assertThatThrownBy(()->OwnedBillingFirstRenewalRuntime.admit(renewal,SHA)).hasMessage("FIRST_RENEWAL_ADMISSION_DENIED");
        var stage4=new HashMap<>(Map.of("PAYMENT_STAGE4_TEST_RUNTIME","true","PAYMENT_STAGE4_CONTROL_KEY",KEY));
        assertThatCode(()->OwnedSubscriptionRuntime.requireOptIn(stage4)).doesNotThrowAnyException();stage4.put("PAYMENT_STRIPE_BILLING_FAILED_RENEWAL","true");
        assertThatThrownBy(()->OwnedSubscriptionRuntime.requireOptIn(stage4))
            .hasMessage("Explicit owned runtime opt-in required");
    }
    @Test void recoveryConfirmationDescriptorUsesThePersistedMicrosecondOfTheOriginalReceipt() {
        var dispatched=Instant.parse("2026-11-08T12:00:00Z");
        var observed=dispatched.plusNanos(123456788);
        var acknowledged=dispatched.plusNanos(123456789);
        var operation=new ProviderOperation(UUID.randomUUID(),new PaymentAccountId(UUID.randomUUID()),"acct_owned","alias",
            ProviderOperation.Kind.OWNED_RECOVERY_INTENT_CONFIRM,UUID.randomUUID(),"owned:CONFIRM","fingerprint",
            ProviderOperation.Status.ACKNOWLEDGED,"pi_owned",dispatched,dispatched.plusSeconds(82800),null,null,2);
        var reply=new ConfirmReply("pi_owned","cus_owned","pm_owned","EUR",2000,2000,0,"ch_owned",observed);
        var ack=new ConfirmationAck(operation,reply,acknowledged);
        var descriptor=new JsonObject();
        OwnedBillingFailedRenewalSandboxScenario.recoveryConfirmationEvidence(descriptor,ack);
        assertThat(descriptor.get("recovery_acknowledged_at").getAsString()).isEqualTo("2026-11-08T12:00:00.123456Z");
        assertThat(ack.acknowledgedAt()).isEqualTo(acknowledged);
        assertThat(ack.operation()).isSameAs(operation);assertThat(ack.reply()).isSameAs(reply);
        assertThat(ack.reply().observedAt()).isEqualTo(observed);
    }

    // Widening a fixed route or the empty body schema would dispatch the inert boundary below.
    @Test void everyFixedRouteDispatchesExactlyItsOwnOperationWithNoStore() throws Exception {
        for(String route:ROUTES){var ops=new Inert();var x=request("POST","/failed-renewal/"+route,"{}");handler(ops).handle(x);
            assertThat(x.code).isEqualTo(200);assertThat(ops.effects).containsExactly(route);assertThat(ops.aborts).hasValue(0);
            assertThat(x.responses.getFirst("Cache-Control")).isEqualTo("no-store");assertThat(x.responses.getFirst("Content-Type")).isEqualTo("application/json");}
    }
    @Test void unauthorizedCallsNeverReadBodiesOrStopTheOwner() throws Exception {
        for(String value:List.of("Bearer wrong","bearer "+KEY,"Bearer "+KEY+" ","")){
            var ops=new Inert();var x=request("POST","/failed-renewal/arm-decline","{}");x.requests.set("Authorization",value);
            x.body=new InputStream(){public int read(){throw new AssertionError("Denied caller body was read");}};handler(ops).handle(x);
            assertThat(x.code).isEqualTo(403);assertThat(x.output.toString()).isEqualTo("{\"code\":\"DENIED\"}");assertThat(ops.effects).isEmpty();assertThat(ops.aborts).hasValue(0);
        }
        var ops=new Inert();var x=request("POST","/failed-renewal/arm-decline","{}");x.requests.add("Authorization","Bearer "+KEY);handler(ops).handle(x);
        assertThat(x.code).isEqualTo(403);assertThat(ops.effects).isEmpty();assertThat(ops.aborts).hasValue(0);
    }
    @Test void invalidMethodsRoutesAndBodiesStopWithNoDispatch() throws Exception {
        for(var entry:List.of(new String[]{"GET","/failed-renewal/boundary","{}","405"},new String[]{"POST","/failed-renewal/boundary?target=1","{}","404"},
            new String[]{"POST","/failed-renewal/clock","{}","404"},new String[]{"POST","/failed-renewal/method/prepare","{\"target\":\"pm_foreign\"}","400"},
            new String[]{"POST","/failed-renewal/failed","[]","400"},new String[]{"POST","/failed-renewal/failed","null","400"},
            new String[]{"POST","/failed-renewal/failed","{}{}","400"},new String[]{"POST","/failed-renewal/failed","{\"x\":\"1\",\"x\":\"2\"}","400"},
            new String[]{"POST","/failed-renewal/failed","{\"nested\":{}}","400"},new String[]{"POST","/failed-renewal/failed"," ".repeat(16385),"400"})){
            var ops=new Inert();var x=request(entry[0],entry[1],entry[2]);handler(ops).handle(x);assertThat(x.code).isEqualTo(Integer.parseInt(entry[3]));
            assertThat(ops.effects).isEmpty();assertThat(ops.aborts).hasValue(1);assertThat(x.output.toString()).doesNotContain("pm_foreign","target");assertThat(x.responses.getFirst("Cache-Control")).isEqualTo("no-store");
        }
    }
    @Test void originalRoutesKeepTheirStrictSchemasAndSafeErrors() throws Exception {
        var ops=new Inert();var h=handler(ops);
        var status=request("GET","/initial-payment/status","");h.handle(status);assertThat(status.code).isEqualTo(200);
        var observe=request("POST","/initial-payment/observe","{}");h.handle(observe);assertThat(observe.code).isEqualTo(200);
        var malformed=request("POST","/initial-payment/confirm","{\"target\":\"foreign\"}");h.handle(malformed);assertThat(malformed.code).isEqualTo(400);
        assertThat(ops.effects).containsExactly("status","initial-observe");assertThat(ops.aborts).hasValue(1);
    }
    @Test void errorsCaptureOnlyClosedStageBeforeAbortAndNeverExceptionDetails() throws Exception {
        for(String stage:List.of("BOUNDARY","DECLINE_ARM","FAILED_RENEWAL","GRACE_LAST_SECOND","GRACE_AT","GRACE_AFTER","METHOD_PREPARE","METHOD_SELECT","METHOD_CONFIRM","METHOD_APPLY","RECOVERY_CONFIRM","RECOVERY_OBSERVE","private-secret")){
            var ops=new Inert(){public JsonObject boundary(){throw new IllegalStateException("sk_test_PRIVATE providerBody");}public String failureStage(){return aborts.get()>0?"INITIAL":stage;}};
            var x=request("POST","/failed-renewal/boundary","{}");handler(ops).handle(x);assertThat(x.code).isEqualTo(400);
            assertThat(x.output.toString()).isEqualTo("{\"code\":\"FAILED_RENEWAL_STOPPED\",\"stage\":\""+(stage.equals("private-secret")?"UNCLASSIFIED":stage)+"\"}");assertThat(ops.aborts).hasValue(1);
        }
    }
    // A competing request must not call abort, cancelling the dispatched winner's authority.
    @Test void concurrentStageLosersCannotDispatchOrStealWinnerCleanup() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failures=new CopyOnWriteArrayList<Throwable>();
        var ops=new Inert(){public JsonObject boundary(){effects.add("boundary");entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("Latch deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return new JsonObject();}};
        var h=handler(ops);var winner=request("POST","/failed-renewal/boundary","{}");
        var thread=new Thread(()->{try{h.handle(winner);}catch(Throwable t){failures.add(t);}},"inert-failed-handler-race");thread.start();
        try{assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            for(var loser:List.of(request("POST","/failed-renewal/boundary","{}"),request("POST","/failed-renewal/arm-decline","{}"),request("POST","/initial-payment/observe","{}"),request("POST","/failed-renewal/failed","{\"target\":\"foreign\"}"))){
                h.handle(loser);assertThat(loser.code).isEqualTo(400);assertThat(loser.output.toString()).isEqualTo("{\"code\":\"FAILED_RENEWAL_STOPPED\"}");}
            assertThat(ops.effects).containsExactly("boundary");assertThat(ops.aborts).hasValue(0);
        }finally{release.countDown();thread.join(3000);}
        assertThat(thread.isAlive()).isFalse();assertThat(failures).isEmpty();assertThat(winner.code).isEqualTo(200);assertThat(ops.aborts).hasValue(0);
    }
    @Test void terminalControlIsAuthenticatedClosedAndConsumed() throws Exception {
        var ops=new Inert();var h=handler(ops);String route="/failed-renewal/abort";
        for(var bad:List.of(request("GET",route,"{}"),request("POST",route,"{\"target\":\"arbitrary\"}"),
                request("POST",route,"{}{}"),request("POST",route,"[]"))){
            h.handle(bad);assertThat(bad.code).isNotEqualTo(200);
            assertThat(ops.aborts).as("malformed terminal request owns no cancellation right").hasValue(0);
        }
        var denied=request("POST",route,"{}");denied.requests.set("Authorization","Bearer wrong");
        h.handle(denied);assertThat(denied.code).isEqualTo(403);assertThat(ops.aborts).hasValue(0);
        var accepted=request("POST",route,"{}");h.handle(accepted);
        assertThat(accepted.code).isEqualTo(200);assertThat(accepted.output.toString()).isEqualTo("{\"code\":\"QUIESCENT\"}");
        assertThat(accepted.responses.getFirst("Cache-Control")).isEqualTo("no-store");assertThat(ops.aborts).hasValue(1);
        var replay=request("POST",route,"{}");h.handle(replay);assertThat(replay.code).isNotEqualTo(200);
        var financial=request("POST","/failed-renewal/arm-decline","{}");h.handle(financial);
        assertThat(financial.code).isNotEqualTo(200);assertThat(ops.effects).isEmpty();assertThat(ops.aborts).hasValue(1);
    }
    private static void denied(Map<String,String> env,AtomicInteger calls){assertThatThrownBy(()->admitted(new String[0],env,a->{calls.incrementAndGet();return a;})).hasMessage("FAILED_RENEWAL_ADMISSION_DENIED");}
    @SuppressWarnings("unchecked") private static <T>T admitted(String[] args,Map<String,String> env,java.util.function.Function<OwnedBillingFirstPaymentRuntime.Admission,T> factory)throws Exception{
        var method=Class.forName(TYPE,false,OwnedBillingFailedRenewalRuntimeTest.class.getClassLoader()).getDeclaredMethod("admitted",String[].class,Map.class,java.util.function.Function.class);method.setAccessible(true);
        try{return (T)method.invoke(null,args,env,factory);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Error error)throw error;throw (Exception)failed.getCause();}
    }
    private static HttpHandler handler(Inert ops)throws Exception{return (HttpHandler)Class.forName(TYPE+"$Handler",false,OwnedBillingFailedRenewalRuntimeTest.class.getClassLoader()).getConstructor(OwnedBillingFailedRenewalOperations.class,String.class).newInstance(ops,KEY);}
    private static MemoryExchange request(String method,String route,String body){return new MemoryExchange(method,route,body);}
    static class Inert implements OwnedBillingFailedRenewalOperations {
        final List<String> effects=new CopyOnWriteArrayList<>();final AtomicInteger aborts=new AtomicInteger();
        public boolean awaitQuiescence(long untilNanos){return true;}
        private JsonObject call(String name){effects.add(name);return new JsonObject();}
        public JsonObject prepare(JsonObject r){return call("initial-prepare");}public JsonObject confirm(JsonObject r){return call("initial-confirm");}
        public JsonObject observe(){return call("initial-observe");}public JsonObject status(){return call("status");}public void abort(){aborts.incrementAndGet();}
        public JsonObject boundary(){return call("boundary");}public JsonObject armDecline(){return call("arm-decline");}public JsonObject failedRenewal(){return call("failed");}
        public JsonObject graceLastSecond(){return call("grace-last-second");}public JsonObject graceAt(){return call("grace-at");}public JsonObject graceAfter(){return call("grace-after");}
        public JsonObject prepareMethod(){return call("method/prepare");}public JsonObject selectMethod(){return call("method/select");}public JsonObject confirmMethod(){return call("method/confirm");}public JsonObject applyMethod(){return call("method/apply");}
        public JsonObject confirmRecovery(){return call("recovery/confirm");}public JsonObject observeRecovery(){return call("recovery/observe");}public String failureStage(){return "UNCLASSIFIED";}
    }
    static final class MemoryExchange extends HttpExchange {
        final String method,path;final Headers requests=new Headers(),responses=new Headers();InputStream body;final ByteArrayOutputStream output=new ByteArrayOutputStream();int code;
        MemoryExchange(String method,String path,String body){this.method=method;this.path=path;this.body=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));requests.add("Authorization","Bearer "+KEY);}
        public Headers getRequestHeaders(){return requests;}public Headers getResponseHeaders(){return responses;}public URI getRequestURI(){return URI.create(path);}public String getRequestMethod(){return method;}
        public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return body;}public OutputStream getResponseBody(){return output;}public void sendResponseHeaders(int code,long length){this.code=code;}
        public InetSocketAddress getRemoteAddress(){return null;}public int getResponseCode(){return code;}public InetSocketAddress getLocalAddress(){return null;}public String getProtocol(){return "HTTP/1.1";}
        public Object getAttribute(String name){return null;}public void setAttribute(String name,Object value){}public void setStreams(InputStream i,OutputStream o){}public HttpPrincipal getPrincipal(){return null;}
    }
}
