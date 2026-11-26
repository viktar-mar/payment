package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OwnedBillingOptionalTrialRuntimeTest {
    private static final String PREFIX = "PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_";
    private static final String CAPABILITY = "q".repeat(43);
    private static final String SOURCE = "a".repeat(40);
    private static final String PATH = "/optional-trial/";
    private static final UUID RUN = UUID.randomUUID();

    @Test void admitsOnlyTheSelectedSourceMatchedLaneWithoutCallerBillingTime() {
        var env = environment();
        var admission = OwnedBillingOptionalTrialRuntime.admit(env);
        assertThat(admission.run()).isEqualTo(RUN);
        assertThat(admission.bind()).isEqualTo("127.0.0.1");
        assertThat(admission.toString()).doesNotContain(CAPABILITY);
        env.put("PAYMENT_RUNTIME_SOURCE_SHA","b".repeat(40));
        assertThatThrownBy(() -> OwnedBillingOptionalTrialRuntime.admit(env)).isInstanceOf(IllegalArgumentException.class);
        env.put("PAYMENT_RUNTIME_SOURCE_SHA",SOURCE);
        env.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT","true");
        assertThatThrownBy(() -> OwnedBillingOptionalTrialRuntime.admit(env)).isInstanceOf(IllegalArgumentException.class);
        env.remove("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT");
        env.put(PREFIX+"FROZEN_TIME","1700000000");
        assertThatThrownBy(() -> OwnedBillingOptionalTrialRuntime.admit(env)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsUnauthorizedAndMalformedOpenBeforeDelegation() throws Exception {
        var calls = new AtomicInteger();
        var handler = handler(new OwnedBillingOptionalTrialRuntime.Operations() {
            public JsonObject dispatch(String phase, JsonObject body) {
                calls.incrementAndGet();
                return stage("TRIALING");
            }
            public void stop() {}
        });
        var forbidden = exchange("open", open(), false);
        handler.handle(forbidden);
        assertThat(forbidden.code).isEqualTo(403);
        var duplicate = exchange("open",open().replace("\"origin\"", "\"accountId\":\"foreign\",\"origin\""),true);
        handler.handle(duplicate);
        assertThat(duplicate.code).isEqualTo(400);
        assertThat(duplicate.output.toString()).doesNotContain("private-token","foreign");
        assertThat(calls.get()).isZero();
    }

    @Test void passesOnlyTheExactOpenAndEmptyPhaseBodies() throws Exception {
        var calls = new AtomicInteger();
        var handler = handler(new OwnedBillingOptionalTrialRuntime.Operations() {
            public JsonObject dispatch(String phase, JsonObject body) {
                calls.incrementAndGet();
                if (phase.equals("open")) {
                    assertThat(body.get("recurringConsent").getAsBoolean()).isTrue();
                    assertThat(body.keySet()).hasSize(7);
                    return stage("TRIALING");
                }
                assertThat(phase).isEqualTo("advance");
                assertThat(body.isEmpty()).isTrue();
                return stage("PAUSED");
            }
            public void stop() {}
        });
        var opening = exchange("open",open(),true);
        handler.handle(opening);
        assertThat(opening.code).isEqualTo(200);
        assertThat(opening.responses.getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(opening.output.toString()).doesNotContain("private-token");
        var advance = exchange("advance","{}",true);
        handler.handle(advance);
        assertThat(advance.code).isEqualTo(200);
        assertThat(JsonParser.parseString(advance.output.toString()).getAsJsonObject().get("stage").getAsString()).isEqualTo("PAUSED");
        var extra = exchange("pay","{\"invoice\":\"foreign\"}",true);
        handler.handle(extra);
        assertThat(extra.code).isEqualTo(400);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void terminalProofWaitsForTrackedTransportAndDisablesFutureRequests() throws Exception {
        var gate = new OwnedBillingOptionalTrialRuntime.Gate();
        var entered = new CountDownLatch(1);
        var remainingTransport = new CountDownLatch(1);
        var stopped = new AtomicInteger();
        var handler = new OwnedBillingOptionalTrialRuntime.Handler(new OwnedBillingOptionalTrialRuntime.Operations() {
            public JsonObject dispatch(String phase, JsonObject body) {
                gate.beginTransport(gate.generation());
                Thread.ofVirtual().start(() -> {
                    try {
                        assertThat(remainingTransport.await(5,TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } finally {
                        gate.endTransport();
                    }
                });
                entered.countDown();
                return stage("TRIALING");
            }
            public void stop() { stopped.incrementAndGet(); }
        },CAPABILITY,gate);

        var open = exchange("open",open(),true);
        handler.handle(open);
        assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
        assertThat(open.code).isEqualTo(200);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var abort = exchange("abort","{}",true);
            var pending = executor.submit(() -> {
                handler.handle(abort);
                return abort;
            });
            try {
                assertThatThrownBy(() -> pending.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                remainingTransport.countDown();
            }
            assertThat(pending.get(5,TimeUnit.SECONDS).code).isEqualTo(200);
            assertThat(abort.output.toString()).isEqualTo("{\"stage\":\"QUIESCENT\"}");
            assertThat(stopped.get()).isOne();
        }
        var replay = exchange("open",open(),true);
        handler.handle(replay);
        assertThat(replay.code).isEqualTo(400);
        assertThatThrownBy(() -> gate.beginTransport(gate.generation())).isInstanceOf(IllegalStateException.class);
    }

    private static Map<String,String> environment() {
        return new HashMap<>(Map.of("PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL","true",
            "PAYMENT_STRIPE_SANDBOX_ENABLED","true","PAYMENT_RUNTIME_SOURCE_SHA",SOURCE,
            PREFIX+"RUN_ID",RUN.toString(),PREFIX+"SOURCE_SHA",SOURCE,PREFIX+"CONTROL_KEY",CAPABILITY,PREFIX+"CONTROL_PORT","8099"));
    }

    private static OwnedBillingOptionalTrialRuntime.Handler handler(OwnedBillingOptionalTrialRuntime.Operations operations) {
        return new OwnedBillingOptionalTrialRuntime.Handler(operations,CAPABILITY,new OwnedBillingOptionalTrialRuntime.Gate());
    }

    private static OwnedBillingFirstPaymentRuntimeTest.MemoryExchange exchange(String phase, String body, boolean authorized) {
        return new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange("POST",PATH+phase,body,authorized);
    }

    private static String open() {
        return """
            {"accountId":"%s","siteId":"%s","customerId":"%s","priceId":"%s",
             "origin":"https://shop.test","customerToken":"private-token","recurringConsent":true}
            """.formatted(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    }

    private static JsonObject stage(String value) {
        var result = new JsonObject();
        result.addProperty("stage",value);
        return result;
    }
}
