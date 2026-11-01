package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonObject;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Pure admission and in-memory HTTP tests; no application, sockets, SDK, or database. */
class OwnedBillingFirstRenewalRuntimeTest {
    static Map<String,String> environment() {
        var env=new HashMap<String,String>();env.put("PAYMENT_STRIPE_BILLING_FIRST_RENEWAL","true");env.put("PAYMENT_STRIPE_SANDBOX_ENABLED","true");
        env.put("PAYMENT_RUNTIME_SOURCE_SHA","a".repeat(40));
        String p="PAYMENT_STRIPE_BILLING_FIRST_RENEWAL_";
        env.put(p+"RUN_ID","cd2116f9-f847-4dc8-85fa-7df63ca51451");env.put(p+"SOURCE_SHA","a".repeat(40));env.put(p+"CONTROL_KEY","q".repeat(43));env.put(p+"FROZEN_TIME","1700000000");env.put(p+"CONTROL_PORT","8099");return env;
    }
    @Test void newGateRequiresItsOwnFlagAndRejectsLegacyAndSourceDriftBeforeFactory() {
        var env=environment();var calls=new ArrayList<Object>();
        assertThat(OwnedBillingFirstRenewalRuntime.admitted(new String[0],env,a->{calls.add(a);return a;}).source()).isEqualTo("a".repeat(40));
        for(String flag:List.of("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT","PAYMENT_STRIPE_BILLING_ACCEPTANCE","PAYMENT_STRIPE_NATIVE_ACCEPTANCE","PAYMENT_STAGE4_TEST_RUNTIME","PAYMENT_STRIPE_SANDBOX_FULL_RUN")){
            var invalid=new HashMap<>(env);invalid.put(flag,"true");assertThatThrownBy(()->OwnedBillingFirstRenewalRuntime.admitted(new String[0],invalid,a->{calls.add(a);return a;})).hasMessage("FIRST_RENEWAL_ADMISSION_DENIED");
        }
        env.put("PAYMENT_RUNTIME_SOURCE_SHA","b".repeat(40));assertThatThrownBy(()->OwnedBillingFirstRenewalRuntime.admitted(new String[0],env,a->{calls.add(a);return a;})).hasMessage("FIRST_RENEWAL_ADMISSION_DENIED");assertThat(calls).hasSize(1);
        var old=new HashMap<String,String>();environment().forEach((k,v)->old.put(k.replace("FIRST_RENEWAL","FIRST_PAYMENT"),v));
        assertThat(OwnedBillingFirstPaymentRuntime.admit(old,"a".repeat(40)).source()).isEqualTo("a".repeat(40));old.put("PAYMENT_STRIPE_BILLING_FIRST_RENEWAL","true");assertThatThrownBy(()->OwnedBillingFirstPaymentRuntime.admit(old,"a".repeat(40))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void inertDiagnosticValuesAndDeadlineAreExact() {
        var env=environment();env.put("DEBUG","");env.put("NODE_DEBUG","");env.put("PWDEBUG","0");env.put("PW_TRACE","off");
        var admitted=OwnedBillingFirstRenewalRuntime.admit(env,"a".repeat(40));var now=Instant.now();
        var scenario=new OwnedBillingFirstRenewalScenario(admitted,t->{throw new AssertionError("expired run cannot resolve dependencies");},Clock.fixed(now,ZoneOffset.UTC),now);
        assertThatThrownBy(scenario::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(scenario.status().get("phase").getAsString()).isEqualTo("STOPPED");
    }
    @ParameterizedTest @ValueSource(strings={"DEBUG","NODE_DEBUG","PWDEBUG","PW_TRACE"})
    void diagnosticEnvironmentCannotAdmitRuntime(String flag) {
        var env=environment();env.put(flag,"1");var calls=new ArrayList<Object>();
        assertThatThrownBy(()->OwnedBillingFirstRenewalRuntime.admitted(new String[0],env,a->{calls.add(a);return a;})).hasMessage("FIRST_RENEWAL_ADMISSION_DENIED");
        assertThat(calls).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"[]","null","{\"target\":\"1700000000\"}","{\"x\":1}","{}{}","{\"x\":\"a\",\"x\":\"b\"}"})
    void onlyExactEmptyObjectsReachStages(String body) throws Exception {
        var ops=new Inert();var x=new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange("POST","/first-renewal/boundary",body,true);
        new OwnedBillingFirstRenewalRuntime.Handler(ops,"q".repeat(43)).handle(x);assertThat(x.code).isEqualTo(400);assertThat(ops.boundaries).isZero();assertThat(ops.aborted).isTrue();assertThat(x.output.toString()).doesNotContain("secret");
    }
    @Test void authMethodsAndFixedRoutesAreBoundedAndOriginalRoutesDelegate() throws Exception {
        var ops=new Inert();var handler=new OwnedBillingFirstRenewalRuntime.Handler(ops,"q".repeat(43));
        var denied=new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange("POST","/first-renewal/boundary","{}",false);handler.handle(denied);assertThat(denied.code).isEqualTo(403);assertThat(ops.boundaries).isZero();
        var boundary=new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange("POST","/first-renewal/boundary","{}",true);handler.handle(boundary);assertThat(boundary.code).isEqualTo(200);assertThat(ops.boundaries).isEqualTo(1);assertThat(boundary.responses.getFirst("Cache-Control")).isEqualTo("no-store");
        var finalization=new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange("POST","/first-renewal/finalization","{}",true);handler.handle(finalization);assertThat(finalization.code).isEqualTo(200);assertThat(ops.finals).isEqualTo(1);
        var initial=new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange("POST","/initial-payment/observe","{}",true);handler.handle(initial);assertThat(initial.code).isEqualTo(200);assertThat(ops.calls).isEqualTo(1);
    }
    @Test void prematureBoundaryOrFinalizationBurnsGenuineWrapperWithoutDependencies() {
        for(boolean boundary:List.of(true,false)){
            var now=Instant.now();var calls=new ArrayList<Class<?>>();
            var scenario=new OwnedBillingFirstRenewalScenario(OwnedBillingFirstRenewalRuntime.admit(environment(),"a".repeat(40)),t->{calls.add(t);throw new AssertionError("No dependency before initial settlement");},Clock.fixed(now,ZoneOffset.UTC),now.plusSeconds(120));
            assertThatThrownBy(()->{if(boundary)scenario.boundary();else scenario.finalization();}).hasMessage("FIRST_RENEWAL_STOPPED");
            assertThatThrownBy(scenario::boundary).hasMessage("FIRST_RENEWAL_STOPPED");assertThatThrownBy(scenario::finalization).hasMessage("FIRST_RENEWAL_STOPPED");assertThat(scenario.status().get("phase").getAsString()).isEqualTo("STOPPED");assertThat(calls).isEmpty();
        }
    }
    static final class Inert extends OwnedBillingFirstPaymentRuntimeTest.InertOperations implements OwnedBillingFirstRenewalRuntime.Operations {
        int boundaries,finals;
        public JsonObject boundary(){boundaries++;return new JsonObject();}
        public JsonObject finalization(){finals++;return new JsonObject();}
    }
}
