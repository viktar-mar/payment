package dev.genericpayments.acceptance.runtime;

import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure tests: reflection keeps the first failing contract runnable before the launcher exists. */
class OwnedBillingFirstPaymentRuntimeTest {
    @Test void hostedScenarioNamesExplicitApplicationRootWithoutInitialization() throws Exception {
        // Never initialize/instantiate this hosted fixture or ask Spring to bootstrap a context.
        var scenario=Class.forName("dev.genericpayments.acceptance.runtime.OwnedBillingFirstPaymentScenarioIntegrationTest",false,getClass().getClassLoader());
        var configuration=scenario.getAnnotation(org.springframework.boot.test.context.SpringBootTest.class);
        assertThat(configuration).isNotNull();
        assertThat(configuration.classes()).extracting(Class::getName)
            .containsExactly("dev.genericpayments.platform.PaymentPlatformApplication");
    }
    /** Shared hosted-fixture precision boundary; never used by the exported runtime. */
    static java.time.Instant observationUtc(java.time.Instant utc) { return utc.truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    @Test void operationalObservationClockDoesNotPrecedeItsPersistedBillingRecord() throws Exception {
        var raw=java.time.Instant.parse("2026-10-30T11:00:00.123456789Z");
        var formatter=new org.postgresql.jdbc.TimestampUtils(false,()->java.util.TimeZone.getTimeZone("UTC"));
        var calendar=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        var rounded=formatter.toTimestamp(calendar,formatter.toString(calendar,java.sql.Timestamp.from(raw))).toInstant();
        assertThat(rounded).isEqualTo(java.time.Instant.parse("2026-10-30T11:00:00.123457Z"));
        assertThatThrownBy(()->billingRecordAt(rounded,raw))
            .isInstanceOf(dev.genericpayments.platform.shared.domain.DomainValidationException.class).hasMessage("Invalid billing record");
        var aligned=observationUtc(raw);
        var persisted=formatter.toTimestamp(calendar,formatter.toString(calendar,java.sql.Timestamp.from(aligned))).toInstant();
        var repeated=billingRecordAt(persisted,aligned);
        assertThat(repeated.createdAt()).isEqualTo(java.time.Instant.parse("2026-10-30T11:00:00.123456Z"));
        assertThat(repeated.updatedAt()).isEqualTo(repeated.createdAt());
    }
    private static dev.genericpayments.platform.subscriptions.domain.BillingRecord billingRecordAt(java.time.Instant created,java.time.Instant updated) {
        var terms=new dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms(
            new dev.genericpayments.platform.catalog.domain.PriceId(new UUID(0,1)),"first-monthly",1,
            new dev.genericpayments.platform.catalog.domain.ProductId(new UUID(0,2)),dev.genericpayments.platform.catalog.domain.Money.of(2000,"EUR"),
            dev.genericpayments.platform.catalog.domain.BillingInterval.MONTH,0,
            dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,dev.genericpayments.platform.catalog.domain.TaxMode.INCLUSIVE);
        return new dev.genericpayments.platform.subscriptions.domain.BillingRecord(new UUID(0,3),
            new dev.genericpayments.platform.sites.domain.SiteId(new UUID(0,4)),new UUID(0,5),
            new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(new UUID(0,6)),"in_original",
            dev.genericpayments.platform.subscriptions.domain.BillingRecord.Kind.INITIAL,terms,null,
            new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(java.time.Instant.parse("2023-11-14T22:13:20Z"),java.time.Instant.parse("2023-12-14T22:13:20Z")),
            2000,"EUR",dev.genericpayments.platform.subscriptions.domain.BillingRecord.Settlement.CONFIRMED_CASH,0,created,updated);
    }
    /** Shared hosted-fixture claim time; no synthetic advance of operational UTC. */
    static java.time.Instant callbackClaimUtc(java.time.Instant utc) { return utc; }
    @Test void callbackClaimCanCompleteAtTheCurrentUtcDeliveryBoundary() {
        var claimedAt=java.time.Instant.parse("2026-10-30T11:00:00.123456789Z");
        var completedAt=java.time.Instant.parse("2026-10-30T11:00:00.124456789Z");
        var claim=new dev.genericpayments.platform.webhooks.application.port.out.OutboxPort.Claim(
            new UUID(0,1),null,new UUID(0,2),"https://callback.invalid",1,
            new dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue(new byte[16],new byte[12],"inert"),
            "{}",1,"first-payment",callbackClaimUtc(claimedAt),claimedAt.minusSeconds(1),
            dev.genericpayments.platform.webhooks.application.port.out.OutboxPort.Mode.AUTOMATIC,
            new dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Context("fixture",dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Provider.STRIPE));
        var completions=new ArrayList<Object[]>();
        var sent=new ArrayList<Map<String,String>>();
        // Capture the actual service's completion arguments at the store boundary, without a database.
        var outbox=(dev.genericpayments.platform.webhooks.application.port.out.OutboxPort)Proxy.newProxyInstance(
            getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.webhooks.application.port.out.OutboxPort.class},
            (proxy,method,args)->switch(method.getName()) {
                case "ambientTransaction" -> false;
                case "complete" -> {completions.add(args);yield true;}
                default -> throw new AssertionError("Unexpected outbox access: "+method.getName());
            });
        var encryption=new dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort() {
            public EncryptedValue encrypt(String plaintext){throw new AssertionError("No encryption write");}
            public String decrypt(EncryptedValue value){return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);}
        };
        new dev.genericpayments.platform.webhooks.application.service.CallbackDeliveryService(outbox,encryption,
            c->c.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            (endpoint,headers,bytes)->{sent.add(headers);return dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort.Result.success(204);},
            java.time.Clock.fixed(completedAt,java.time.ZoneOffset.UTC)).deliver(claim);
        assertThat(sent).hasSize(1);
        assertThat(java.time.Instant.ofEpochSecond(Long.parseLong(sent.getFirst().get("Payment-Timestamp"))))
            .isEqualTo(java.time.Instant.parse("2026-10-30T11:00:00Z"));
        assertThat(sent.getFirst().get("Payment-Signature")).startsWith("v1=");
        assertThat(completions).hasSize(1);
        assertThat(completions.getFirst()[0]).isSameAs(claim);
        assertThat(completions.getFirst()[1]).isEqualTo(dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort.Result.success(204));
        assertThat((java.time.Instant)completions.getFirst()[2]).isEqualTo(completedAt).isAfterOrEqualTo(claim.startedAt());
    }
    static Map<String,String> environment() {


        var env=new HashMap<String,String>();
        env.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT","true");
        env.put("PAYMENT_STRIPE_SANDBOX_ENABLED","true");
        env.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_RUN_ID","cd2116f9-f847-4dc8-85fa-7df63ca51451");
        env.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_SOURCE_SHA","a".repeat(40));
        env.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_FROZEN_TIME","1700000000");
        env.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_CONTROL_KEY","q".repeat(43));
        env.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_CONTROL_PORT","8099");
        return env;
    }
    static Object admit(Map<String,String> env) throws Throwable {
        try { return Class.forName("dev.genericpayments.acceptance.runtime.OwnedBillingFirstPaymentRuntime")
            .getDeclaredMethod("admit",Map.class,String.class).invoke(null,env,"a".repeat(40)); }
        catch(InvocationTargetException e){throw e.getCause();}
    }
    @Test void missingOrConflictingOptInCannotReachRuntimeConstruction() throws Throwable {
        assertThatThrownBy(()->admit(Map.of())).isInstanceOf(IllegalArgumentException.class);
        for(String key:List.of("PAYMENT_STRIPE_BILLING_ACCEPTANCE","PAYMENT_STAGE4_TEST_RUNTIME","PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS","PAYMENT_STRIPE_SANDBOX_OWNED_RUN","PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT")) {
            var env=environment();env.put(key,"true");
            assertThatThrownBy(()->admit(env)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(admit(environment())).isNotNull();
    }
    @Test void runSourceFrozenAndCapabilityMustBeAdmittedBeforeContext() {
        for(var bad:Map.of("RUN_ID","not-a-uuid","SOURCE_SHA","b".repeat(40),"FROZEN_TIME","9007199254740992","CONTROL_KEY","secret","CONTROL_PORT","80").entrySet()) {
            var env=environment();env.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_"+bad.getKey(),bad.getValue());
            assertThatThrownBy(()->admit(env)).isInstanceOf(IllegalArgumentException.class).hasMessage("INITIAL_PAYMENT_ADMISSION_DENIED");
        }
    }
    @Test void duplicateOrExpiredTransitionBurnsTheRun() {
        var now=java.time.Instant.parse("2026-10-30T12:00:00Z");
        var state=new OwnedBillingFirstPaymentRuntime.State(java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),now.plusSeconds(1));
        state.begin("NEW","PREPARING");state.finish("PREPARING","PREPARED");
        assertThatThrownBy(()->state.begin("NEW","PREPARING")).isInstanceOf(IllegalStateException.class);
        assertThat(state.phase()).isEqualTo("STOPPED");
        assertThatThrownBy(()->state.begin("STOPPED","NEW")).isInstanceOf(IllegalStateException.class);
        var expired=new OwnedBillingFirstPaymentRuntime.State(java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),now);
        assertThatThrownBy(()->expired.begin("NEW","PREPARING")).isInstanceOf(IllegalStateException.class);
        assertThat(expired.phase()).isEqualTo("STOPPED");
    }
    @Test void observationsAreBoundedAndCannotConfirmAgain() {
        var now=java.time.Instant.parse("2026-10-30T12:00:00Z");
        var state=new OwnedBillingFirstPaymentRuntime.State(java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),now.plusSeconds(1));
        state.begin("NEW","OBSERVE");for(int i=0;i<30;i++)state.observe();
        assertThatThrownBy(state::observe).isInstanceOf(IllegalStateException.class);
        assertThat(state.phase()).isEqualTo("STOPPED");
    }
    @Test void authenticationPrecedesReadingAndCannotExposeFailureSecrets() throws Exception {
        var operations=new InertOperations();var handler=new OwnedBillingFirstPaymentRuntime.Handler(operations,"q".repeat(43));
        var denied=new MemoryExchange("POST","/initial-payment/prepare","secret-body",false);
        denied.body=new java.io.InputStream(){public int read(){throw new AssertionError("Unauthorized body was read");}};
        handler.handle(denied);assertThat(denied.code).isEqualTo(403);assertThat(operations.calls).isZero();assertThat(operations.aborted).isFalse();
        var failure=new MemoryExchange("POST","/initial-payment/observe","{}",true);operations.failure=true;
        handler.handle(failure);assertThat(failure.code).isEqualTo(400);assertThat(failure.output.toString()).isEqualTo("{\"code\":\"INITIAL_PAYMENT_STOPPED\"}");assertThat(operations.aborted).isTrue();
        assertThat(failure.responses.getFirst("Cache-Control")).isEqualTo("no-store");
    }
    @Test void unexpectedPathMethodFieldsDuplicateKeysAndOversizeNeverDispatch() throws Exception {
        for(var entry:List.of(new String[]{"GET","/initial-payment/observe","{}"},new String[]{"POST","/initial-payment/observe?x=y","{}"},
            new String[]{"POST","/initial-payment/observe","{\"sql\":\"secret\"}"},new String[]{"POST","/initial-payment/confirm","{\"attempt_id\":\"a\",\"attempt_id\":\"b\"}"},
            new String[]{"POST","/initial-payment/observe"," ".repeat(16385)},new String[]{"POST","/initial-payment/observe","{} {}"},new String[]{"GET","/initial-payment/status","{}"})) {
            var operations=new InertOperations();var handler=new OwnedBillingFirstPaymentRuntime.Handler(operations,"q".repeat(43));
            var request=new MemoryExchange(entry[0],entry[1],entry[2],true);handler.handle(request);
            assertThat(request.code).isGreaterThanOrEqualTo(400);assertThat(operations.calls).isZero();assertThat(operations.aborted).isTrue();
        }
    }
    static final class InertOperations implements OwnedBillingFirstPaymentRuntime.Operations {
        int calls;boolean aborted,failure;
        public com.google.gson.JsonObject prepare(com.google.gson.JsonObject r){calls++;return r;}
        public com.google.gson.JsonObject confirm(com.google.gson.JsonObject r){calls++;return r;}
        public com.google.gson.JsonObject observe(){calls++;if(failure)throw new IllegalStateException("ctoken_SECRET sk_test_SECRET");return new com.google.gson.JsonObject();}
        public com.google.gson.JsonObject status(){calls++;return new com.google.gson.JsonObject();}
        public void abort(){aborted=true;}
    }
    static final class MemoryExchange extends com.sun.net.httpserver.HttpExchange {
        final String method,path;final com.sun.net.httpserver.Headers requests=new com.sun.net.httpserver.Headers(),responses=new com.sun.net.httpserver.Headers();
        java.io.InputStream body;final java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();int code;
        MemoryExchange(String method,String path,String body,boolean authorized){this.method=method;this.path=path;this.body=new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));if(authorized)requests.add("Authorization","Bearer "+"q".repeat(43));}
        public com.sun.net.httpserver.Headers getRequestHeaders(){return requests;}public com.sun.net.httpserver.Headers getResponseHeaders(){return responses;}
        public java.net.URI getRequestURI(){return java.net.URI.create(path);}public String getRequestMethod(){return method;}
        public com.sun.net.httpserver.HttpContext getHttpContext(){return null;}public void close(){}public java.io.InputStream getRequestBody(){return body;}public java.io.OutputStream getResponseBody(){return output;}
        public void sendResponseHeaders(int code,long length){this.code=code;}public java.net.InetSocketAddress getRemoteAddress(){return null;}public int getResponseCode(){return code;}public java.net.InetSocketAddress getLocalAddress(){return null;}public String getProtocol(){return "HTTP/1.1";}
        public Object getAttribute(String name){return null;}public void setAttribute(String name,Object value){}public void setStreams(java.io.InputStream i,java.io.OutputStream o){}public com.sun.net.httpserver.HttpPrincipal getPrincipal(){return null;}
    }

}
