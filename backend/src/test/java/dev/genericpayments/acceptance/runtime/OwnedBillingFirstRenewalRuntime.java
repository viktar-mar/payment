package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.*;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.support.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Separate opt-in future launcher; neither scanned nor packaged in the ordinary application. */
public final class OwnedBillingFirstRenewalRuntime {
    private OwnedBillingFirstRenewalRuntime() {}
    public interface Operations extends OwnedBillingFirstPaymentRuntime.Operations {
        JsonObject boundary();
        JsonObject finalization();
        default String boundaryFailureStage(){return "UNCLASSIFIED";}
    }
    public static OwnedBillingFirstPaymentRuntime.Admission admit(Map<String,String> env,String source) {
        try {
            if(!"true".equals(env.get("PAYMENT_STRIPE_BILLING_FIRST_RENEWAL"))||!Set.of("","false").contains(env.getOrDefault("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT","")))throw new IllegalArgumentException();
            if(!env.getOrDefault("DEBUG","").isEmpty()||!env.getOrDefault("NODE_DEBUG","").isEmpty()
                ||!Set.of("","0").contains(env.getOrDefault("PWDEBUG",""))||!Set.of("","off").contains(env.getOrDefault("PW_TRACE","")))throw new IllegalArgumentException();
            var normalized=new HashMap<>(env);normalized.remove("PAYMENT_STRIPE_BILLING_FIRST_RENEWAL");normalized.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT","true");
            for(String field:List.of("RUN_ID","SOURCE_SHA","FROZEN_TIME","CONTROL_KEY","CONTROL_PORT","CONTROL_BIND")){
                normalized.remove(OwnedBillingFirstPaymentRuntime.PREFIX+field);
                var value=env.get("PAYMENT_STRIPE_BILLING_FIRST_RENEWAL_"+field);if(value!=null)normalized.put(OwnedBillingFirstPaymentRuntime.PREFIX+field,value);
            }
            return OwnedBillingFirstPaymentRuntime.admit(normalized,source);
        }catch(RuntimeException failure){throw new IllegalArgumentException("FIRST_RENEWAL_ADMISSION_DENIED");}
    }
    static <T>T admitted(String[] args,Map<String,String> env,java.util.function.Function<OwnedBillingFirstPaymentRuntime.Admission,T> factory){
        if(args.length!=0)throw new IllegalArgumentException("FIRST_RENEWAL_ADMISSION_DENIED");return factory.apply(admit(env,env.get("PAYMENT_RUNTIME_SOURCE_SHA")));
    }
    public static final class Handler implements HttpHandler {
        private final Operations operations;private final byte[] authorization;
        private final OwnedBillingFirstPaymentRuntime.Handler initial;
        public Handler(Operations operations,String capability){this.operations=operations;authorization=("Bearer "+capability).getBytes(StandardCharsets.US_ASCII);initial=new OwnedBillingFirstPaymentRuntime.Handler(operations,capability);}
        @Override public void handle(HttpExchange exchange)throws IOException {
            String path=exchange.getRequestURI().toString();
            if(!Set.of("/first-renewal/boundary","/first-renewal/finalization").contains(path)){initial.handle(exchange);return;}
            try {
                var auth=exchange.getRequestHeaders().get("Authorization");
                if(auth==null||auth.size()!=1||!MessageDigest.isEqual(authorization,auth.getFirst().getBytes(StandardCharsets.UTF_8))){OwnedBillingFirstPaymentRuntime.Handler.reply(exchange,403,OwnedBillingFirstPaymentRuntime.error("DENIED"));return;}
                if(!exchange.getRequestMethod().equals("POST")){operations.abort();OwnedBillingFirstPaymentRuntime.Handler.reply(exchange,405,OwnedBillingFirstPaymentRuntime.error("INVALID_REQUEST"));return;}
                var body=OwnedBillingFirstPaymentRuntime.Handler.read(exchange,false);OwnedBillingFirstPaymentRuntime.fields(body,Set.of());
                var response=path.equals("/first-renewal/boundary")?operations.boundary():operations.finalization();OwnedBillingFirstPaymentRuntime.Handler.reply(exchange,200,response);
            }catch(Exception failure){operations.abort();var response=OwnedBillingFirstPaymentRuntime.error("FIRST_RENEWAL_STOPPED");
                if(path.equals("/first-renewal/boundary")){
                    String stage;try{stage=operations.boundaryFailureStage();}catch(RuntimeException ignored){stage="UNCLASSIFIED";}
                    if(stage==null||!Set.of("INITIAL_RECEIPT","ADMISSION","CLOCK_ADVANCE","CLOCK_ADVANCE_AUTHORITY","CLOCK_ADVANCE_PREPARE","CLOCK_ADVANCE_ACCOUNT","CLOCK_ADVANCE_RESERVE","CLOCK_ADVANCE_CLAIM","CLOCK_ADVANCE_CLIENT","CLOCK_ADVANCE_PRE_READ","CLOCK_ADVANCE_PRE_VALIDATE","CLOCK_ADVANCE_POST","CLOCK_ADVANCE_POST_VALIDATE","CLOCK_ADVANCE_ACK_STORE","CLOCK_ADVANCE_POLL_WAIT","CLOCK_ADVANCE_POLL_READ","CLOCK_ADVANCE_POLL_VALIDATE","CLOCK_ADVANCE_POLL_EXHAUSTED","CLOCK_ADVANCE_READY_STORE","GRAPH","GRAPH_OBSERVER_INIT","GRAPH_CLAIM",
                        "GRAPH_CLOCK_BEFORE","GRAPH_REMOTE_PRECHECK","GRAPH_CUSTOMER","GRAPH_SUBSCRIPTION","GRAPH_INVOICES",
                        "GRAPH_INITIAL_INVOICE","GRAPH_RENEWAL_INVOICE","GRAPH_CLOCK_AFTER","GRAPH_ACCEPT","GRAPH_ASSERT","BOUNDARY_RESPONSE").contains(stage))stage="UNCLASSIFIED";
                    response.addProperty("stage",stage);
                }
                OwnedBillingFirstPaymentRuntime.Handler.reply(exchange,400,response);}
            finally{exchange.close();}
        }
    }
    @Configuration(proxyBeanMethods=false)
    public static class BillingTimeConfiguration {
        @Bean @Primary OwnedFirstRenewalBillingTime ownedFirstRenewalBillingTime(JdbcTemplate jdbc){return new OwnedFirstRenewalBillingTime(jdbc,new OwnedSubscriptionBillingTime(jdbc));}
    }
    public static void main(String[] args)throws Exception {
        var resources=admitted(args,System.getenv(),OwnedBillingFirstPaymentRuntime.StartupResources::new);var admission=resources.admission;
        var deadline=Instant.now().plus(Duration.ofMinutes(45));
        var context=resources.acquireContext(()->{
            System.setProperty("payment.subscriptions.scheduling-enabled","false");var app=new SpringApplication(PaymentPlatformApplication.class,BillingTimeConfiguration.class);app.setAdditionalProfiles("local");return app.run();
        },org.springframework.context.ConfigurableApplicationContext::close);
        resources.run(()->{context.addApplicationListener(event->{if(event instanceof org.springframework.context.event.ContextClosedEvent closing&&closing.getApplicationContext()==context)resources.contextClosed();});resources.checkContextAfterListenerRegistration(context.isClosed(),context.isActive());});
        var scenario=resources.acquire(()->new OwnedBillingFirstRenewalScenario(admission,context::getBean,Clock.systemUTC(),deadline),OwnedBillingFirstRenewalScenario::abort);
        var server=resources.acquire(()->HttpServer.create(new InetSocketAddress(admission.bind(),admission.port()),4),s->s.stop(0));
        var executor=resources.acquire(Executors::newVirtualThreadPerTaskExecutor,ExecutorService::shutdownNow);
        resources.run(()->server.setExecutor(executor));resources.run(()->server.createContext("/",new Handler(scenario,admission.capability())));resources.run(server::start);
    }
}
