package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.stripe.exception.StripeException;
import com.stripe.net.HttpClient;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialAcceptance;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;

/** A private control listener for an explicitly selected, fresh optional-trial run. */
public final class OwnedBillingOptionalTrialRuntime {
    public static final String CONNECTION_INIT_SQL =
        "set jit=off; set join_collapse_limit=1; set from_collapse_limit=1";
    private static final String PREFIX = "PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_";
    private static final String PATH = "/optional-trial/";
    private static final Set<String> PHASES = Set.of("open","advance","setup-permit","setup-install","resume","pay","abort");
    private static final Set<String> OPEN_FIELDS = Set.of("accountId","siteId","customerId","priceId","origin","customerToken","recurringConsent");
    private static final Set<String> FAILURE_STAGES = Set.of("OPTIONAL_TRIAL_ADMISSION","OPTIONAL_TRIAL_CREATE","OPTIONAL_TRIAL_TRIALING",
        "OPTIONAL_TRIAL_ADVANCE_AND_PAUSE","OPTIONAL_TRIAL_SETUP_PERMIT","OPTIONAL_TRIAL_SETUP_INSTALL",
        "OPTIONAL_TRIAL_PAYMENT_CONFIRMATION","OPTIONAL_TRIAL_PAYMENT_OBSERVATION");
    private static final Set<String> RESUME_FAILURE_STAGES = Set.of(
        "OPTIONAL_TRIAL_RESUME_OPEN","OPTIONAL_TRIAL_RESUME_PRECHECK","OPTIONAL_TRIAL_RESUME_OBSERVE_CLOCK","OPTIONAL_TRIAL_RESUME_OBSERVE_SUBSCRIPTION",
        "OPTIONAL_TRIAL_RESUME_OBSERVE_CASH","OPTIONAL_TRIAL_RESUME_CLAIM","OPTIONAL_TRIAL_RESUME_DISPATCH_CLOCK",
        "OPTIONAL_TRIAL_RESUME_DISPATCH_RESPONSE","OPTIONAL_TRIAL_RESUME_STRIPE_INVALID_REQUEST",
        "OPTIONAL_TRIAL_RESUME_STRIPE_BILLING_CYCLE_ANCHOR","OPTIONAL_TRIAL_RESUME_STRIPE_PAYMENT_BEHAVIOR",
        "OPTIONAL_TRIAL_RESUME_STRIPE_PRORATION_BEHAVIOR","OPTIONAL_TRIAL_RESUME_READ_INVOICE","OPTIONAL_TRIAL_RESUME_FINISH",
        "OPTIONAL_TRIAL_RESUME_RESULT","OPTIONAL_TRIAL_RESUME_PROJECT",
        "OPTIONAL_TRIAL_RESUME_RESPONSE_INVOICE_INVALID",
        "OPTIONAL_TRIAL_RESUME_RESPONSE_INVOICE_ORIGINAL",
        "OPTIONAL_TRIAL_RESUME_RESPONSE_STRUCTURE",
        "OPTIONAL_TRIAL_RESUME_RESPONSE_STATUS",
        "OPTIONAL_TRIAL_RESUME_RESPONSE_OWNER",
        "OPTIONAL_TRIAL_RESUME_RESPONSE_PENDING_UPDATE",
        "OPTIONAL_TRIAL_RESUME_RESPONSE_PERIOD",
        "OPTIONAL_TRIAL_RESUME_RESPONSE_STRIPE");

    private OwnedBillingOptionalTrialRuntime() {}

    record Admission(UUID run, String source, String capability, int port, String bind) {
        @Override public String toString() { return "OwnedBillingOptionalTrialRuntime.Admission[redacted]"; }
    }

    static Admission admit(Map<String,String> env) {
        try {
            if (!"true".equals(env.get("PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL"))
                || !"true".equals(env.get("PAYMENT_STRIPE_SANDBOX_ENABLED"))
                || !env.getOrDefault(PREFIX+"FROZEN_TIME","").isEmpty()) throw stopped();
            for (var flag : List.of("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT","PAYMENT_STRIPE_BILLING_FIRST_RENEWAL",
                    "PAYMENT_STRIPE_BILLING_FAILED_RENEWAL","PAYMENT_STRIPE_BILLING_ACCEPTANCE","PAYMENT_STAGE4_TEST_RUNTIME",
                    "PAYMENT_STRIPE_ACCEPTANCE","PAYMENT_STRIPE_NATIVE_ACCEPTANCE")) requireInactive(env,flag);
            for (var flag : List.of("ALLOW_TEST_OPERATIONS","FULL_RUN","OWNED_RUN","REMAINING_ONLY","FORM_DIAGNOSTIC",
                    "MANUAL_FORM_DIAGNOSTIC","ALLOW_PREPARE_ONLY","MANUAL_PAYMENT","MANUAL_3DS","MANUAL_REFUND","MANUAL_REFUND_REMAINDER")) {
                requireInactive(env,"PAYMENT_STRIPE_SANDBOX_"+flag);
            }
            var run = uuid(env.get(PREFIX+"RUN_ID"));
            var source = env.get(PREFIX+"SOURCE_SHA");
            var capability = env.get(PREFIX+"CONTROL_KEY");
            var bind = env.getOrDefault(PREFIX+"CONTROL_BIND","127.0.0.1");
            var portText = env.get(PREFIX+"CONTROL_PORT");
            if (source == null || !source.matches("[0-9a-f]{40}") || !source.equals(env.get("PAYMENT_RUNTIME_SOURCE_SHA"))
                || capability == null || !capability.matches("[A-Za-z0-9_-]{43}")
                || !Set.of("127.0.0.1","0.0.0.0").contains(bind) || portText == null || !portText.matches("[0-9]{4,5}")) {
                throw stopped();
            }
            int port = Integer.parseInt(portText);
            if (port < 1024 || port > 65535) throw stopped();
            return new Admission(run,source,capability,port,bind);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("OPTIONAL_TRIAL_ADMISSION_DENIED");
        }
    }

    private static void requireInactive(Map<String,String> env, String flag) {
        if (!Set.of("","false").contains(env.getOrDefault(flag,""))) throw stopped();
    }

    static UUID uuid(String text) {
        var value = UUID.fromString(text);
        if (!value.toString().equals(text)) throw stopped();
        return value;
    }

    interface Operations {
        JsonObject dispatch(String phase, JsonObject body);
        void stop();
    }

    /** The abort barrier covers both the active phase and SDK calls still returning on workers. */
    static final class Gate {
        private boolean accepting = true;
        private boolean producer;
        private boolean abortClaimed;
        private int transports;
        private long generation;

        synchronized boolean enter() {
            if (!accepting || producer || transports != 0) return false;
            producer = true;
            generation++;
            return true;
        }

        synchronized void leave() {
            producer = false;
            notifyAll();
        }

        synchronized void stop() {
            accepting = false;
        }

        synchronized void requireAccepting() {
            if (!accepting) throw stopped();
        }

        synchronized boolean claimAbort() {
            if (abortClaimed) return false;
            abortClaimed = true;
            stop();
            return true;
        }

        synchronized boolean awaitQuiescence(long until) throws InterruptedException {
            while (producer || transports != 0) {
                long remaining = until - System.nanoTime();
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(this,remaining);
            }
            return until - System.nanoTime() > 0;
        }

        synchronized long generation() {
            return generation;
        }

        synchronized void beginTransport(long selectedGeneration) {
            if (!accepting || !producer || selectedGeneration != generation) throw stopped();
            transports++;
        }

        synchronized void endTransport() {
            transports--;
            notifyAll();
        }
    }

    static final class Handler implements HttpHandler {
        private final Operations operations;
        private final byte[] authorization;
        private final Gate gate;

        Handler(Operations operations, String capability, Gate gate) {
            this.operations = operations;
            this.authorization = ("Bearer "+capability).getBytes(StandardCharsets.US_ASCII);
            this.gate = gate;
        }

        @Override public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                var auth = exchange.getRequestHeaders().get("Authorization");
                if (auth == null || auth.size() != 1 || !MessageDigest.isEqual(authorization,auth.getFirst().getBytes(StandardCharsets.UTF_8))) {
                    reply(exchange,403,status("DENIED"));
                    return;
                }
                var path = exchange.getRequestURI().toString();
                if (!path.startsWith(PATH) || !PHASES.contains(path.substring(PATH.length()))) {
                    reply(exchange,404,status("INVALID_REQUEST"));
                    return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) {
                    reply(exchange,405,status("INVALID_REQUEST"));
                    return;
                }
                var phase = path.substring(PATH.length());
                JsonObject body;
                try {
                    body = read(exchange);
                    if (phase.equals("open")) {
                        if (!body.keySet().equals(OPEN_FIELDS)) throw stopped();
                        for (var key : List.of("accountId","siteId","customerId","priceId")) uuid(body.get(key).getAsString());
                    } else if (!body.isEmpty()) {
                        throw stopped();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    reply(exchange,400,status("INVALID_REQUEST"));
                    return;
                } catch (Exception invalid) {
                    reply(exchange,400,status("INVALID_REQUEST"));
                    return;
                }
                if (phase.equals("abort")) {
                    abort(exchange);
                    return;
                }
                if (!gate.enter()) {
                    reply(exchange,400,status("OPTIONAL_TRIAL_STOPPED"));
                    return;
                }
                ScopedClients.bind(gate);
                try {
                    var result = operations.dispatch(phase,body);
                    gate.requireAccepting();
                    reply(exchange,200,result);
                } catch (RuntimeException failure) {
                    gate.stop();
                    operations.stop();
                    var error = status("OPTIONAL_TRIAL_STOPPED");
                    String code = failure.getMessage();
                    if (code != null && (FAILURE_STAGES.contains(code) || phase.equals("resume") && RESUME_FAILURE_STAGES.contains(code))) {
                        error.addProperty("code",code);
                    }
                    reply(exchange,400,error);
                } catch (IOException lostReply) {
                    gate.stop();
                    operations.stop();
                    throw lostReply;
                } finally {
                    ScopedClients.unbind();
                    gate.leave();
                }
            }
        }

        private void abort(HttpExchange exchange) throws IOException {
            if (gate.claimAbort()) {
                try {
                    if (gate.awaitQuiescence(System.nanoTime()+TimeUnit.SECONDS.toNanos(25))) {
                        operations.stop();
                        reply(exchange,200,status("QUIESCENT"));
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            reply(exchange,400,status("OPTIONAL_TRIAL_STOPPED"));
        }

        private static JsonObject read(HttpExchange exchange) throws Exception {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var task = executor.submit(() -> exchange.getRequestBody().readNBytes(16385));
            byte[] bytes;
            try {
                bytes = task.get(5,TimeUnit.SECONDS);
            } finally {
                task.cancel(true);
                executor.shutdownNow();
            }
            if (bytes.length > 16384) throw stopped();
            var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            var reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            var body = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                var name = reader.nextName();
                if (body.has(name)) throw stopped();
                if ("recurringConsent".equals(name)) {
                    if (reader.peek() != JsonToken.BOOLEAN || !reader.nextBoolean()) throw stopped();
                    body.addProperty(name,true);
                } else {
                    if (reader.peek() != JsonToken.STRING) throw stopped();
                    body.addProperty(name,reader.nextString());
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw stopped();
            return body;
        }

        private static JsonObject status(String value) {
            var body = new JsonObject();
            body.addProperty("stage",value);
            return body;
        }

        private static void reply(HttpExchange exchange, int code, JsonObject response) throws IOException {
            var bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(code,bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    static final class ScopedClients extends StripeClientFactory {
        private record Ticket(Gate gate, long generation) {}

        private static final InheritableThreadLocal<Ticket> CURRENT = new InheritableThreadLocal<>();

        static void bind(Gate gate) {
            CURRENT.set(new Ticket(gate,gate.generation()));
        }

        static void unbind() {
            CURRENT.remove();
        }

        @Override protected HttpClient transport() {
            var delegate = super.transport();
            var ticket = CURRENT.get();
            return ticket == null ? delegate : new TrackedTransport(delegate,ticket);
        }

        private static final class TrackedTransport extends HttpClient {
            private final HttpClient delegate;
            private final Ticket ticket;

            TrackedTransport(HttpClient delegate, Ticket ticket) {
                this.delegate = delegate;
                this.ticket = ticket;
            }

            @Override public StripeResponse request(StripeRequest request) throws StripeException {
                ticket.gate().beginTransport(ticket.generation());
                try {
                    return delegate.request(request);
                } finally {
                    ticket.gate().endTransport();
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(OwnedOptionalTrialAcceptance.Configuration.class)
    static class RuntimeConfiguration {
        @Bean @Primary ScopedClients optionalTrialScopedStripeClients() { return new ScopedClients(); }

        @Bean FlywayMigrationStrategy optionalTrialMigrations() {
            return main -> {
                main.migrate();
                Flyway.configure().dataSource(main.getConfiguration().getDataSource())
                    .locations("classpath:db/owned-optional-trial").table("owned_optional_trial_schema_history")
                    .baselineOnMigrate(true).baselineVersion("0").load().migrate();
            };
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("OPTIONAL_TRIAL_ADMISSION_DENIED");
        var admission = admit(System.getenv());
        var utc = Clock.systemUTC();
        var deadline = utc.instant().plus(Duration.ofMinutes(45));
        System.setProperty("payment.subscriptions.scheduling-enabled","false");
        var app = new SpringApplication(PaymentPlatformApplication.class,RuntimeConfiguration.class);
        // Preserve the explicit join order in the deeply nested ownership and claim proofs.
        app.setDefaultProperties(Map.of("spring.datasource.hikari.connection-init-sql",CONNECTION_INIT_SQL));
        app.setAdditionalProfiles("local");
        var context = app.run();
        var gate = new Gate();
        try {
            var scenario = new OwnedBillingOptionalTrialScenario(admission,context,utc,deadline);
            var server = HttpServer.create(new InetSocketAddress(admission.bind(),admission.port()),4);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                context.addApplicationListener(event -> {
                    if (event instanceof ContextClosedEvent close && close.getApplicationContext() == context) {
                        gate.stop();
                        server.stop(0);
                        executor.shutdownNow();
                    }
                });
                if (!context.isActive() || context.isClosed()) throw stopped();
                server.setExecutor(executor);
                server.createContext("/",new Handler(scenario,admission.capability(),gate));
                server.start();
            } catch (Exception | Error startupFailure) {
                server.stop(0);
                executor.shutdownNow();
                throw startupFailure;
            }
        } catch (Exception | Error startupFailure) {
            context.close();
            throw startupFailure;
        }
    }

    static IllegalStateException stopped() { return new IllegalStateException("OPTIONAL_TRIAL_STOPPED"); }
}
