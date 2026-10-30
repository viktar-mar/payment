package dev.genericpayments.acceptance.runtime;

import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure tests: reflection keeps the first failing contract runnable before the launcher exists. */
class OwnedBillingFirstPaymentRuntimeTest {
    // Missing unwind, reordered effects, or duplicate disposal breaks the actual guard contracts below.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"context","listener","scenario","server","executor","set-executor","handler","start"})
    void startupFailureDisposesOnlyAlreadyAcquiredResourcesAndStopsSetup(String boundary) throws Exception {
        var calls=new ArrayList<String>();var guard=new StartupFixture(calls);var failure=new java.io.IOException("inert");
        assertThatThrownBy(()->guard.start(boundary,failure)).isSameAs(failure);
        var acquired=switch(boundary) {
            case "context" -> List.<String>of();
            case "listener","scenario" -> List.of("context");
            case "server" -> List.of("scenario","context");
            case "executor" -> List.of("scenario","server","context");
            default -> List.of("scenario","server","executor","context");
        };
        assertThat(guard.closed).containsExactlyElementsOf(acquired);
        assertThat(calls.getLast()).isEqualTo(boundary);
        guard.close();guard.contextClosed();assertThat(guard.closed).containsExactlyElementsOf(acquired);
        assertThatThrownBy(()->guard.run(()->calls.add("provider"))).isInstanceOf(IllegalStateException.class);
        assertThat(calls).doesNotContain("provider");
    }
    @Test void successfulStartupLivesUntilContextCloseWithoutRecursiveContextDisposal() throws Exception {
        var guard=new StartupFixture(new ArrayList<>());guard.start(null,new java.io.IOException("unused"));
        assertThat(guard.closed).isEmpty();
        // The context already owns its normal close. Its event must close children only.
        guard.contextClosed();guard.contextClosed();guard.close();
        assertThat(guard.closed).containsExactly("scenario","server","executor");
        assertThatThrownBy(()->guard.acquire("late",false,()->"late")).isInstanceOf(IllegalStateException.class);
        assertThat(guard.closed).containsExactly("scenario","server","executor");
    }
    @Test void everyCleanupIsAttemptedEvenWhenAllThrowAndOriginalStartupFailureSurvives() throws Exception {
        var guard=new StartupFixture(new ArrayList<>());guard.throwOnClose=true;
        var failure=hostileFailure();assertThatThrownBy(()->guard.start("start",failure)).isSameAs(failure);
        assertThat(guard.closed).containsExactly("scenario","server","executor","context");
        guard.close();assertThat(guard.closed).hasSize(4);
    }
    @Test void normalContextCloseContinuesAfterThrowingChildCleanup() throws Exception {
        var guard=new StartupFixture(new ArrayList<>());guard.start(null,new java.io.IOException("unused"));guard.throwOnClose=true;
        guard.contextClosed();guard.close();assertThat(guard.closed).containsExactly("scenario","server","executor");
    }
    // A close event may already have selected its listeners while Spring still reports active.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true,true","true,false,true","false,false,true","false,true,false"})
    void postRegistrationContextStateStopsMissedCloseBeforeLaterEffects(boolean closing,boolean active,boolean stopped) throws Exception {
        var guard=new StartupFixture(new ArrayList<>());guard.acquire("context",true,()->"context");
        org.assertj.core.api.ThrowableAssert.ThrowingCallable registered=()->guard.run(()->{
            guard.calls.add("listener-registered");
            // No context-close event is delivered to the guard in this regression.
            guard.checkContextAfterListenerRegistration(closing,active);
        });
        if(stopped) {
            assertThatThrownBy(registered).isInstanceOf(IllegalStateException.class);
            for(String name:List.of("scenario","server","executor"))
                assertThatThrownBy(()->guard.acquire(name,false,()->{guard.calls.add(name);return name;})).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->guard.run(()->guard.calls.add("start-or-provider"))).isInstanceOf(IllegalStateException.class);
            guard.close();guard.contextClosed();
            assertThat(guard.calls).containsExactly("listener-registered");
            assertThat(guard.closed).as("context shutdown is already owned externally").isEmpty();
        } else {
            assertThatCode(registered).doesNotThrowAnyException();
            guard.acquire("scenario",false,()->{guard.calls.add("scenario");return "scenario";});
            assertThat(guard.closed).isEmpty();assertThat(guard.calls).containsExactly("listener-registered","scenario");
            guard.contextClosed();assertThat(guard.closed).containsExactly("scenario");
        }
    }
    @Test void contextCloseDuringListenerRegistrationPreventsAllLaterAcquisition() throws Exception {
        var guard=new StartupFixture(new ArrayList<>());guard.acquire("context",true,()->"context");
        assertThatThrownBy(()->guard.run(()->{guard.contextClosedUnchecked();})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->guard.acquire("scenario",false,()->{guard.calls.add("provider");return "scenario";})).isInstanceOf(IllegalStateException.class);
        assertThat(guard.calls).doesNotContain("provider");assertThat(guard.closed).isEmpty();
    }
    @Test void contextCloseDuringAcquisitionDisposesReturnedResourceWithoutInstallingIt() throws Exception {
        var guard=new StartupFixture(new ArrayList<>());guard.acquire("context",true,()->"context");
        assertThatThrownBy(()->guard.acquire("server",false,()->{guard.contextClosed();return "server";})).isInstanceOf(IllegalStateException.class);
        guard.close();assertThat(guard.closed).containsExactly("server");
    }
    @Test void contextCloseInterleavedWithFailureUnwindDoesNotCloseContextAgain() throws Exception {
        var guard=new StartupFixture(new ArrayList<>());guard.onChildClose=guard::contextClosedUnchecked;
        var failure=hostileFailure();assertThatThrownBy(()->guard.start("start",failure)).isSameAs(failure);
        assertThat(guard.closed).containsExactly("scenario","server","executor");guard.close();assertThat(guard.closed).hasSize(3);
    }
    @Test void startupErrorsAlsoUnwindAndDoNotDispatchLaterSetup() throws Exception {
        var guard=new StartupFixture(new ArrayList<>());var failure=new AssertionError("inert");
        assertThatThrownBy(()->guard.start("handler",failure)).isSameAs(failure);
        assertThat(guard.closed).containsExactly("scenario","server","executor","context");assertThat(guard.calls).doesNotContain("start");
    }
    @Test void argumentAndEnvironmentAdmissionRejectBeforeLifecycleFactory() throws Exception {
        var methods=Arrays.stream(OwnedBillingFirstPaymentRuntime.class.getDeclaredMethods()).map(Method::getName).toList();
        assertThat(methods).as("main must use an admission-gated lifecycle factory").contains("admitted");
        var method=OwnedBillingFirstPaymentRuntime.class.getDeclaredMethod("admitted",String[].class,Map.class,java.util.function.Function.class);
        var calls=new ArrayList<Object>();java.util.function.Function<OwnedBillingFirstPaymentRuntime.Admission,Object> factory=a->{calls.add(a);return a;};
        var env=environment();env.put("PAYMENT_RUNTIME_SOURCE_SHA","a".repeat(40));
        assertThatThrownBy(()->invoke(method,null,new String[]{"--unsafe"},env,factory)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->invoke(method,null,new String[0],Map.of(),factory)).isInstanceOf(IllegalArgumentException.class);assertThat(calls).isEmpty();
        assertThat(invoke(method,null,new String[0],env,factory)).isSameAs(calls.getFirst());assertThat(calls).hasSize(1);
    }
    private static Object invoke(Method method,Object receiver,Object... args) throws Exception {
        try{return method.invoke(receiver,args);}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof Error error)throw error;throw (Exception)failure.getCause();
        }
    }
    private static final class StartupFixture {
        final Object guard;final Class<?> type;final List<String> calls,closed=new ArrayList<>();boolean throwOnClose;Runnable onChildClose=()->{};
        StartupFixture(List<String> calls) throws Exception {
            this.calls=calls;
            var types=Arrays.stream(OwnedBillingFirstPaymentRuntime.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("StartupResources")).toList();
            assertThat(types).as("actual startup must own and unwind acquired resources").hasSize(1);
            type=types.getFirst();var constructor=type.getDeclaredConstructor(OwnedBillingFirstPaymentRuntime.Admission.class);constructor.setAccessible(true);
            guard=constructor.newInstance(OwnedBillingFirstPaymentRuntime.admit(environment(),"a".repeat(40)));
        }
        Object acquire(String name,boolean context,java.util.concurrent.Callable<String> factory) throws Exception {
            java.util.function.Consumer<String> dispose=value->{closed.add(value);if(context)contextClosedUnchecked();else onChildClose.run();if(throwOnClose)throw hostileFailure();};
            return invoke(type.getDeclaredMethod(context?"acquireContext":"acquire",java.util.concurrent.Callable.class,java.util.function.Consumer.class),guard,factory,dispose);
        }
        void run(Runnable action) throws Exception {invoke(type.getDeclaredMethod("run",Runnable.class),guard,action);}
        void checkContextAfterListenerRegistration(boolean closing,boolean active) {
            try{invoke(type.getDeclaredMethod("checkContextAfterListenerRegistration",boolean.class,boolean.class),guard,closing,active);}
            catch(Exception failure){throw new AssertionError(failure);}
        }
        void close() throws Exception {invoke(type.getDeclaredMethod("close"),guard);}
        void contextClosed() throws Exception {invoke(type.getDeclaredMethod("contextClosed"),guard);}
        void contextClosedUnchecked(){try{contextClosed();}catch(Exception failure){throw new AssertionError(failure);}}
        void start(String failAt,Throwable failure) throws Exception {
            for(String boundary:List.of("context","listener","scenario","server","executor","set-executor","handler","start")) {
                if(Set.of("context","scenario","server","executor").contains(boundary))acquire(boundary,boundary.equals("context"),()->{
                    calls.add(boundary);if(boundary.equals(failAt)){if(failure instanceof Error error)throw error;throw (Exception)failure;}return boundary;});
                else run(()->{calls.add(boundary);if(boundary.equals(failAt)){if(failure instanceof Error error)throw error;if(failure instanceof RuntimeException runtime)throw runtime;
                    // Checked acquisition failures are covered above; wiring throws runtime failures in production.
                    sneaky(failure);}});
            }
        }
        @SuppressWarnings("unchecked") private static <E extends Throwable> void sneaky(Throwable failure) throws E {throw (E)failure;}
    }

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
    // Losing the prepare checkpoint, exposing exception details, or dispatching after failure breaks these contracts.
    @Test void authenticatedPrepareFailuresExposeOnlyClosedCheckpointDto() throws Exception {
        for(String stage:List.of("REQUEST","PREFLIGHT","CLOCK_ADMISSION","CLOCK_CREATE","CUSTOMER_CREATE","LOCAL_RESERVE","SUBSCRIPTION_CREATE","LOCAL_RECORD","RECEIPT","ACTION_SELECT","SESSION_SELECT","RESPONSE_BUILD","UNCLASSIFIED")) {
            var operations=new InertOperations(){public String prepareFailureStage(){return stage;}};operations.failure=true;
            var exchange=new MemoryExchange("POST","/initial-payment/prepare",prepareRequest().toString(),true);
            new OwnedBillingFirstPaymentRuntime.Handler(operations,"q".repeat(43)).handle(exchange);
            assertThat(exchange.code).isEqualTo(400);
            assertThat(exchange.output.toString()).isEqualTo("{\"code\":\"INITIAL_PAYMENT_STOPPED\",\"stage\":\""+stage+"\"}");
            assertThat(operations.calls).isEqualTo(1);assertThat(operations.aborted).isTrue();
        }
    }
    // A dropped mapping or widened metadata allowlist changes the actual Handler wire output.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ACTION_SCOPE","ACTION_AUTHORITY","ACTION_VERIFICATION","ACTION_GRAPH","ACTION_RESERVATION","ACTION_RESULT","ACTION_BINDING"})
    void actionFailureStagesReachHandlerBeforeAbort(String stage) throws Exception {
        var operations=new InertOperations(){public String prepareFailureStage(){return aborted?"REQUEST":stage;}};
        operations.failure=true;
        var exchange=new MemoryExchange("POST","/initial-payment/prepare",prepareRequest().toString(),true);
        new OwnedBillingFirstPaymentRuntime.Handler(operations,"q".repeat(43)).handle(exchange);
        assertThat(exchange.code).isEqualTo(400);
        assertThat(exchange.output.toString()).isEqualTo("{\"code\":\"INITIAL_PAYMENT_STOPPED\",\"stage\":\""+stage+"\"}");
        assertThat(operations.calls).isEqualTo(1);assertThat(operations.aborted).isTrue();
    }
    @Test void scenarioMapsOnlyAdapterSelectionMetadataAndKeepsFallback() throws Exception {
        var mapping=OwnedBillingFirstPaymentScenario.class.getDeclaredMethod("actionFailureStage",String.class,String.class);
        for(String stage:List.of("ACTION_AUTHORITY","ACTION_VERIFICATION","ACTION_GRAPH","ACTION_RESERVATION")) {
            assertThat(mapping.invoke(null,"ACTION_SELECT",stage)).isEqualTo(stage);
            // Even recognized stale adapter metadata cannot relabel the surrounding scenario boundary.
            for(String checkpoint:List.of("ACTION_SCOPE","ACTION_RESULT","ACTION_BINDING","RECEIPT","REQUEST"))
                assertThat(mapping.invoke(null,checkpoint,stage)).isEqualTo(checkpoint);
        }
        for(String stage:Arrays.asList(null,"","ACTION_SCOPE","ACTION_RESULT","ACTION_BINDING","ACTION_SELECT","sk_test_SECRET","ACTION_GRAPH\n"))
            assertThat(mapping.invoke(null,"ACTION_SELECT",stage)).isEqualTo("ACTION_SELECT");
    }
    @Test void unknownDefaultAndThrowingStageProvidersCannotLeakOrInspectExceptions() throws Exception {
        for(var operations:List.of(new InertOperations(),
            new InertOperations(){public String prepareFailureStage(){return "sk_test_SECRET";}},
            new InertOperations(){public String prepareFailureStage(){return null;}},
            new InertOperations(){public String prepareFailureStage(){throw hostileFailure();}})) {
            operations.failure=true;
            var exchange=new MemoryExchange("POST","/initial-payment/prepare",prepareRequest().toString(),true);
            new OwnedBillingFirstPaymentRuntime.Handler(operations,"q".repeat(43)).handle(exchange);
            assertThat(exchange.code).isEqualTo(400);
            assertThat(exchange.output.toString()).isEqualTo("{\"code\":\"INITIAL_PAYMENT_STOPPED\",\"stage\":\"UNCLASSIFIED\"}");
        }
    }
    @Test void authenticatedPrepareParsingFailureIsRequestBeforeOperationsOrStageProvider() throws Exception {
        for(String body:List.of("{}","{\"run_id\":\"a\",\"run_id\":\"b\"}","{", " ".repeat(16385))) {
            var operations=new InertOperations(){public String prepareFailureStage(){throw new AssertionError("No dispatch checkpoint available");}};
            var exchange=new MemoryExchange("POST","/initial-payment/prepare",body,true);
            new OwnedBillingFirstPaymentRuntime.Handler(operations,"q".repeat(43)).handle(exchange);
            assertThat(exchange.code).isEqualTo(400);
            assertThat(exchange.output.toString()).isEqualTo("{\"code\":\"INITIAL_PAYMENT_STOPPED\",\"stage\":\"REQUEST\"}");
            assertThat(operations.calls).isZero();assertThat(operations.aborted).isTrue();
        }
    }
    @Test void stageIsCapturedBeforeAbortAndOtherErrorsKeepTheirProtocol() throws Exception {
        var operations=new InertOperations(){public String prepareFailureStage(){return aborted?"REQUEST":"SUBSCRIPTION_CREATE";}};operations.failure=true;
        var handler=new OwnedBillingFirstPaymentRuntime.Handler(operations,"q".repeat(43));
        var prepare=new MemoryExchange("POST","/initial-payment/prepare",prepareRequest().toString(),true);handler.handle(prepare);
        assertThat(prepare.output.toString()).isEqualTo("{\"code\":\"INITIAL_PAYMENT_STOPPED\",\"stage\":\"SUBSCRIPTION_CREATE\"}");
        var confirm=new MemoryExchange("POST","/initial-payment/confirm","{\"attempt_id\":\"a\",\"capability\":\"b\",\"confirmation_token\":\"c\",\"idempotency_key\":\"d\"}",true);handler.handle(confirm);
        assertThat(confirm.code).isEqualTo(400);assertThat(confirm.output.toString()).isEqualTo("{\"code\":\"INITIAL_PAYMENT_STOPPED\"}");
        var unauthorized=new MemoryExchange("POST","/initial-payment/prepare",prepareRequest().toString(),false);handler.handle(unauthorized);
        assertThat(unauthorized.output.toString()).isEqualTo("{\"code\":\"DENIED\"}");
        var wrongMethod=new MemoryExchange("GET","/initial-payment/prepare","{}",true);handler.handle(wrongMethod);
        assertThat(wrongMethod.code).isEqualTo(405);assertThat(wrongMethod.output.toString()).isEqualTo("{\"code\":\"INVALID_REQUEST\"}");
        var wrongPath=new MemoryExchange("POST","/initial-payment/prepare?x=y","{}",true);handler.handle(wrongPath);
        assertThat(wrongPath.code).isEqualTo(404);assertThat(wrongPath.output.toString()).isEqualTo("{\"code\":\"INVALID_REQUEST\"}");
    }
    @Test void actualScenarioRequestFailureBurnsRunBeforeAnyDependency() throws Exception {
        var dependencies=new ArrayList<Class<?>>();
        var scenario=scenario(type->{dependencies.add(type);throw new AssertionError("Request must reject before dependency access");});
        assertThatThrownBy(()->scenario.prepare(new com.google.gson.JsonObject())).isInstanceOf(IllegalStateException.class).hasMessage("INITIAL_PAYMENT_STOPPED");
        assertThat(scenario.prepareFailureStage()).isEqualTo("REQUEST");assertThat(scenario.status().get("phase").getAsString()).isEqualTo("STOPPED");
        scenario.abort();assertThatThrownBy(()->scenario.prepare(prepareRequest())).isInstanceOf(IllegalStateException.class);
        assertThat(scenario.prepareFailureStage()).isEqualTo("REQUEST");assertThat(dependencies).isEmpty();
    }
    @Test void actualScenarioMalformedAccountIdentifierIsRequestBeforePreflight() throws Exception {
        var dependencies=new ArrayList<Class<?>>();var scenario=scenario(type->{dependencies.add(type);throw hostileFailure();});
        var request=prepareRequest();request.addProperty("account_id","invalid-account-id");
        assertThatThrownBy(()->scenario.prepare(request)).isInstanceOf(IllegalStateException.class).hasMessage("INITIAL_PAYMENT_STOPPED");
        assertThat(scenario.prepareFailureStage()).isEqualTo("REQUEST");assertThat(dependencies).isEmpty();
    }
    @Test void successfulPrepareKeepsItsOriginalDtoAndDoesNotQueryFailureStage() throws Exception {
        var operations=new InertOperations(){public String prepareFailureStage(){throw new AssertionError("No failed prepare");}};
        var exchange=new MemoryExchange("POST","/initial-payment/prepare",prepareRequest().toString(),true);
        new OwnedBillingFirstPaymentRuntime.Handler(operations,"q".repeat(43)).handle(exchange);
        assertThat(exchange.code).isEqualTo(200);assertThat(exchange.output.toString()).isEqualTo(prepareRequest().toString());
        assertThat(operations.calls).isEqualTo(1);assertThat(operations.aborted).isFalse();
    }
    @Test void actualScenarioPreflightFailureSurvivesDuplicateAndAbortWithoutDownstreamEffects() throws Exception {
        var dependencies=new ArrayList<Class<?>>();
        var scenario=scenario(type->{dependencies.add(type);throw hostileFailure();});
        var handler=new OwnedBillingFirstPaymentRuntime.Handler(scenario,"q".repeat(43));
        var first=new MemoryExchange("POST","/initial-payment/prepare",prepareRequest().toString(),true);handler.handle(first);
        assertThat(first.output.toString()).isEqualTo("{\"code\":\"INITIAL_PAYMENT_STOPPED\",\"stage\":\"PREFLIGHT\"}");
        assertThat(dependencies).containsExactly(dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort.class);
        scenario.abort();
        var duplicate=new MemoryExchange("POST","/initial-payment/prepare",prepareRequest().toString(),true);handler.handle(duplicate);
        assertThat(duplicate.output.toString()).isEqualTo("{\"code\":\"INITIAL_PAYMENT_STOPPED\",\"stage\":\"PREFLIGHT\"}");
        assertThat(scenario.prepareFailureStage()).isEqualTo("PREFLIGHT");assertThat(scenario.status().get("phase").getAsString()).isEqualTo("STOPPED");
        assertThat(dependencies).hasSize(1);
    }
    @Test void actualScenarioAccountLookupIsInertAndCustomerAuthorityFailureStopsPreflight() throws Exception {
        var dependencies=new ArrayList<Class<?>>();
        var account=dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount.create(
            new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(new UUID(0,1)),"inert-account",
            dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE,dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode.SANDBOX,
            "acct_1UPiMpELswAd8tpR","stripe-sandbox-default");
        var accountPort=(dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort.class},(proxy,method,args)->{
                assertThat(method.getName()).isEqualTo("findById");assertThat(args).containsExactly(account.id());return Optional.of(account);});
        var sessionPort=(dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver.class},(proxy,method,args)->{
                assertThat(method.getName()).isEqualTo("resolveRequired");assertThat(args).containsExactly("inert-customer-token","https://fixture.invalid");throw hostileFailure();});
        var scenario=scenario(type->{dependencies.add(type);if(type==dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort.class)return accountPort;
            if(type==dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver.class)return sessionPort;throw new AssertionError("No downstream effect");});
        assertThatThrownBy(()->scenario.prepare(prepareRequest())).isInstanceOf(IllegalStateException.class).hasMessage("INITIAL_PAYMENT_STOPPED");
        assertThat(scenario.prepareFailureStage()).isEqualTo("PREFLIGHT");assertThat(dependencies).containsExactly(
            dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort.class,dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver.class);
        assertThatThrownBy(()->scenario.prepare(prepareRequest())).isInstanceOf(IllegalStateException.class);assertThat(dependencies).hasSize(2);
    }
    private static RuntimeException hostileFailure() {
        return new IllegalStateException("ctoken_SECRET sk_test_SECRET") {
            @Override public String getMessage(){throw new AssertionError("Do not inspect dependency message");}
            @Override public String toString(){throw new AssertionError("Do not inspect dependency exception");}
        };
    }
    private static OwnedBillingFirstPaymentScenario scenario(java.util.function.Function<Class<?>,Object> beans) {
        var now=java.time.Instant.parse("2026-10-30T12:00:00Z");
        return new OwnedBillingFirstPaymentScenario(OwnedBillingFirstPaymentRuntime.admit(environment(),"a".repeat(40)),beans,
            java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),now.plusSeconds(30));
    }
    private static com.google.gson.JsonObject prepareRequest() {
        var request=new com.google.gson.JsonObject();request.addProperty("run_id","cd2116f9-f847-4dc8-85fa-7df63ca51451");request.addProperty("source_sha","a".repeat(40));
        for(String name:List.of("account_id","site_id","customer_id","price_id"))request.addProperty(name,new UUID(0,1).toString());
        request.addProperty("origin","https://fixture.invalid");request.addProperty("create_key","first-create");request.addProperty("customer_token","inert-customer-token");return request;
    }
    static class InertOperations implements OwnedBillingFirstPaymentRuntime.Operations {
        int calls;boolean aborted,failure;
        public com.google.gson.JsonObject prepare(com.google.gson.JsonObject r){calls++;if(failure)throw hostileFailure();return r;}
        public com.google.gson.JsonObject confirm(com.google.gson.JsonObject r){calls++;if(failure)throw hostileFailure();return r;}
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
