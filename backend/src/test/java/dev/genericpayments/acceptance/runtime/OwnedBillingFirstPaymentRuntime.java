package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.sun.net.httpserver.*;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.support.OwnedSubscriptionBillingTime;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit future launcher, outside platform scanning and absent from the production jar. */
public final class OwnedBillingFirstPaymentRuntime {
    private OwnedBillingFirstPaymentRuntime() {}
    private static final Set<String> PREPARE_FAILURE_STAGES=Set.of("REQUEST","PREFLIGHT","CLOCK_ADMISSION","CLOCK_CREATE","CUSTOMER_CREATE",
        "LOCAL_RESERVE","SUBSCRIPTION_CREATE","LOCAL_RECORD","RECEIPT","ACTION_SELECT","SESSION_SELECT","RESPONSE_BUILD","UNCLASSIFIED");
    static final String PREFIX="PAYMENT_STRIPE_BILLING_FIRST_PAYMENT_";
    public record Admission(UUID run, String source, long frozen, String capability, int port, String bind) {
        @Override public String toString(){return "Admission[redacted]";}
    }
    public static Admission admit(Map<String,String> env,String source) {
        try {
            if(!"true".equals(env.get("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT"))||!"true".equals(env.get("PAYMENT_STRIPE_SANDBOX_ENABLED")))throw new IllegalArgumentException();
            for(String flag:List.of("PAYMENT_STRIPE_BILLING_ACCEPTANCE","PAYMENT_STAGE4_TEST_RUNTIME","PAYMENT_STRIPE_ACCEPTANCE","PAYMENT_STRIPE_NATIVE_ACCEPTANCE"))
                if(!Set.of("","false").contains(env.getOrDefault(flag,"")))throw new IllegalArgumentException();
            for(String name:List.of("ALLOW_TEST_OPERATIONS","FULL_RUN","OWNED_RUN","REMAINING_ONLY","FORM_DIAGNOSTIC","MANUAL_FORM_DIAGNOSTIC","ALLOW_PREPARE_ONLY","MANUAL_PAYMENT","MANUAL_3DS","MANUAL_REFUND","MANUAL_REFUND_REMAINDER"))
                if(!Set.of("","false").contains(env.getOrDefault("PAYMENT_STRIPE_SANDBOX_"+name,"")))throw new IllegalArgumentException();
            String run=env.get(PREFIX+"RUN_ID"),sha=env.get(PREFIX+"SOURCE_SHA"),key=env.get(PREFIX+"CONTROL_KEY"),f=env.get(PREFIX+"FROZEN_TIME");
            if(run==null||!UUID.fromString(run).toString().equals(run)||sha==null||!sha.matches("[0-9a-f]{40}")||!sha.equals(source)
                ||key==null||!key.matches("[A-Za-z0-9_-]{43}")||f==null||!f.matches("[1-9][0-9]{0,15}"))throw new IllegalArgumentException();
            long frozen=Long.parseLong(f);if(frozen>9007199254740991L)throw new IllegalArgumentException();Instant.ofEpochSecond(frozen);
            int port=Integer.parseInt(env.get(PREFIX+"CONTROL_PORT"));if(port<1024||port>65535)throw new IllegalArgumentException();
            String bind=env.getOrDefault(PREFIX+"CONTROL_BIND","127.0.0.1");if(!Set.of("127.0.0.1","0.0.0.0").contains(bind))throw new IllegalArgumentException();
            return new Admission(UUID.fromString(run),sha,frozen,key,port,bind);
        }catch(RuntimeException invalid){throw new IllegalArgumentException("INITIAL_PAYMENT_ADMISSION_DENIED");}
    }
    public interface Operations {
        JsonObject prepare(JsonObject request);
        default String prepareFailureStage(){return "UNCLASSIFIED";}
        JsonObject confirm(JsonObject request);
        JsonObject observe();
        JsonObject status();
        void abort();
    }
    /** State changes happen before effects. Any failed/duplicate request burns the run permanently. */
    public static final class State {
        private final Clock utc;private final Instant deadline;private String phase="NEW";private int reads;
        public State(Clock utc,Instant deadline){this.utc=utc;this.deadline=deadline;}
        public synchronized void begin(String expected,String next){check();if(!phase.equals(expected)){abort();throw denied();}phase=next;}
        public synchronized void finish(String expected,String next){begin(expected,next);}
        public synchronized void observe(){check();if(!Set.of("OBSERVE","SETTLED_LOCAL").contains(phase)||++reads>30){abort();throw denied();}}
        public synchronized void check(){if(!utc.instant().isBefore(deadline)||phase.equals("STOPPED")){abort();throw denied();}}
        public synchronized String phase(){if(!utc.instant().isBefore(deadline))abort();return phase;}
        public synchronized void settled(){check();phase="SETTLED_LOCAL";}
        public synchronized void abort(){phase="STOPPED";}
    }
    static IllegalStateException denied(){return new IllegalStateException("INITIAL_PAYMENT_STOPPED");}
    public static final class Handler implements HttpHandler {
        private final Operations operations;private final byte[] authorization;
        public Handler(Operations operations,String capability){this.operations=operations;this.authorization=("Bearer "+capability).getBytes(StandardCharsets.US_ASCII);}
        @Override public void handle(HttpExchange exchange) throws IOException {
            boolean prepare=false,prepareDispatched=false;
            try {
                var headers=exchange.getRequestHeaders().get("Authorization");
                if(headers==null||headers.size()!=1||!MessageDigest.isEqual(authorization,headers.getFirst().getBytes(StandardCharsets.UTF_8))){reply(exchange,403,error("DENIED"));return;}
                String path=exchange.getRequestURI().toString();
                if(!Set.of("/initial-payment/prepare","/initial-payment/confirm","/initial-payment/observe","/initial-payment/status").contains(path)){operations.abort();reply(exchange,404,error("INVALID_REQUEST"));return;}
                boolean status=path.endsWith("/status");
                if(!exchange.getRequestMethod().equals(status?"GET":"POST")){operations.abort();reply(exchange,405,error("INVALID_REQUEST"));return;}
                prepare=path.equals("/initial-payment/prepare");
                JsonObject request=read(exchange,status);
                JsonObject response=switch(path){
                    case "/initial-payment/prepare" -> {fields(request,Set.of("run_id","source_sha","account_id","site_id","customer_id","price_id","origin","create_key","customer_token"));prepareDispatched=true;yield operations.prepare(request);}
                    case "/initial-payment/confirm" -> {fields(request,Set.of("attempt_id","capability","confirmation_token","idempotency_key"));yield operations.confirm(request);}
                    case "/initial-payment/observe" -> {fields(request,Set.of());yield operations.observe();}
                    default -> operations.status();
                };
                reply(exchange,200,response);
            }catch(Exception failure){
                var response=prepare?prepareError(prepareDispatched):error("INITIAL_PAYMENT_STOPPED");
                operations.abort();reply(exchange,400,response);
            }
            finally{exchange.close();}
        }
        private JsonObject prepareError(boolean dispatched) {
            String stage="REQUEST";
            if(dispatched)try{stage=operations.prepareFailureStage();}catch(RuntimeException ignored){stage="UNCLASSIFIED";}
            var result=error("INITIAL_PAYMENT_STOPPED");
            result.addProperty("stage",stage!=null&&PREPARE_FAILURE_STAGES.contains(stage)?stage:"UNCLASSIFIED");return result;
        }
        private static JsonObject read(HttpExchange exchange,boolean empty) throws Exception {
            var executor=Executors.newVirtualThreadPerTaskExecutor();
            Future<byte[]> task=executor.submit(()->exchange.getRequestBody().readNBytes(16385));
            byte[] bytes;
            try{bytes=task.get(5,TimeUnit.SECONDS);}finally{task.cancel(true);executor.shutdownNow();}
            if(bytes.length>16384)throw denied();
            if(empty){if(bytes.length!=0)throw denied();return new JsonObject();}
            // Streaming parser rejects duplicate keys, nested/non-string values, trailing data, and lenient JSON.
            var reader=new JsonReader(new StringReader(new String(bytes,StandardCharsets.UTF_8)));reader.setStrictness(Strictness.STRICT);
            var result=new JsonObject();reader.beginObject();
            while(reader.hasNext()) {String name=reader.nextName();if(result.has(name)||reader.peek()!=com.google.gson.stream.JsonToken.STRING)throw denied();result.addProperty(name,reader.nextString());}
            reader.endObject();if(reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw denied();return result;
        }
        private static void reply(HttpExchange exchange,int status,JsonObject response) throws IOException {
            byte[] body=response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
        }
    }
    public static void fields(JsonObject request,Set<String> expected){if(request==null||!request.keySet().equals(expected))throw denied();}
    public static JsonObject error(String code){var result=new JsonObject();result.addProperty("code",code);return result;}
    @Configuration(proxyBeanMethods=false)
    public static class BillingTimeConfiguration {
        @Bean @Primary OwnedSubscriptionBillingTime ownedInitialBillingTime(JdbcTemplate jdbc){return new OwnedSubscriptionBillingTime(jdbc);}
    }
    public static void main(String[] args) throws Exception {
        // No application construction before the complete environment gate. No launch arguments override isolation.
        if(args.length!=0)throw new IllegalArgumentException("INITIAL_PAYMENT_ADMISSION_DENIED");
        var env=System.getenv();var admission=admit(env,env.get("PAYMENT_RUNTIME_SOURCE_SHA"));
        var deadline=Instant.now().plus(Duration.ofMinutes(45));
        System.setProperty("payment.subscriptions.scheduling-enabled","false");
        var app=new SpringApplication(PaymentPlatformApplication.class,BillingTimeConfiguration.class);app.setAdditionalProfiles("local");
        var context=app.run();
        var scenario=new OwnedBillingFirstPaymentScenario(admission,context::getBean,Clock.systemUTC(),deadline);
        var server=HttpServer.create(new InetSocketAddress(admission.bind(),admission.port()),4);
        var executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/",new Handler(scenario,admission.capability()));
        context.addApplicationListener(event->{if(event instanceof org.springframework.context.event.ContextClosedEvent){scenario.abort();server.stop(0);executor.shutdownNow();}});
        server.start();
    }
}
