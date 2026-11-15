package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonObject;
import com.stripe.exception.StripeException;
import com.stripe.net.HttpClient;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import com.sun.net.httpserver.*;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.provider.adapter.out.persistence.OwnedFailedGraceBillingTime;
import dev.genericpayments.platform.support.*;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dedicated future local launcher, absent from ordinary scanning and the production jar. */
public final class OwnedBillingFailedRenewalRuntime {
    private static final String PREFIX="PAYMENT_STRIPE_BILLING_FAILED_RENEWAL_";
    private OwnedBillingFailedRenewalRuntime(){}
    public static OwnedBillingFirstPaymentRuntime.Admission admit(Map<String,String> env,String source){
        try{
            if(!"true".equals(env.get("PAYMENT_STRIPE_BILLING_FAILED_RENEWAL"))||!Objects.equals(source,env.get("PAYMENT_RUNTIME_SOURCE_SHA")))throw new IllegalArgumentException();
            for(String flag:List.of("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT","PAYMENT_STRIPE_BILLING_FIRST_RENEWAL"))
                if(!Set.of("","false").contains(env.getOrDefault(flag,"")))throw new IllegalArgumentException();
            if(!env.getOrDefault("DEBUG","").isEmpty()||!env.getOrDefault("NODE_DEBUG","").isEmpty()
                ||!Set.of("","0").contains(env.getOrDefault("PWDEBUG",""))||!Set.of("","off").contains(env.getOrDefault("PW_TRACE","")))throw new IllegalArgumentException();
            var port=env.get(PREFIX+"CONTROL_PORT");if(port==null||!port.matches("[1-9][0-9]{3,4}"))throw new IllegalArgumentException();
            var normalized=new HashMap<>(env);normalized.remove("PAYMENT_STRIPE_BILLING_FAILED_RENEWAL");normalized.put("PAYMENT_STRIPE_BILLING_FIRST_PAYMENT","true");
            for(String field:List.of("RUN_ID","SOURCE_SHA","FROZEN_TIME","CONTROL_KEY","CONTROL_PORT","CONTROL_BIND")){
                normalized.remove(OwnedBillingFirstPaymentRuntime.PREFIX+field);
                var value=env.get(PREFIX+field);if(value!=null)normalized.put(OwnedBillingFirstPaymentRuntime.PREFIX+field,value);
            }
            return OwnedBillingFirstPaymentRuntime.admit(normalized,source);
        }catch(RuntimeException invalid){throw new IllegalArgumentException("FAILED_RENEWAL_ADMISSION_DENIED");}
    }
    static <T>T admitted(String[] args,Map<String,String> env,java.util.function.Function<OwnedBillingFirstPaymentRuntime.Admission,T> factory){
        if(args==null||args.length!=0)throw new IllegalArgumentException("FAILED_RENEWAL_ADMISSION_DENIED");
        return factory.apply(admit(env,env.get("PAYMENT_RUNTIME_SOURCE_SHA")));
    }
    public static final class Handler implements HttpHandler {
        private static final Set<String> PATHS=Set.of("/failed-renewal/boundary","/failed-renewal/arm-decline","/failed-renewal/failed",
            "/failed-renewal/grace-last-second","/failed-renewal/grace-at","/failed-renewal/grace-after","/failed-renewal/method/prepare",
            "/failed-renewal/method/select","/failed-renewal/method/confirm","/failed-renewal/method/apply","/failed-renewal/recovery/confirm","/failed-renewal/recovery/observe");
        private static final Set<String> STAGES=Set.of("UNCLASSIFIED","INITIAL","BOUNDARY","DECLINE_ARM","FAILED_RENEWAL","GRACE_LAST_SECOND","GRACE_AT","GRACE_AFTER",
            "METHOD_PREPARE","METHOD_SELECT","METHOD_CONFIRM","METHOD_APPLY","RECOVERY_CONFIRM","RECOVERY_OBSERVE");
        private final OwnedBillingFailedRenewalOperations operations;private final byte[] authorization;
        private final OwnedBillingFirstPaymentRuntime.Handler initial;private boolean active,terminal;
        public Handler(OwnedBillingFailedRenewalOperations operations,String capability){this.operations=Objects.requireNonNull(operations);authorization=("Bearer "+capability).getBytes(StandardCharsets.US_ASCII);initial=new OwnedBillingFirstPaymentRuntime.Handler(operations,capability);}
        private synchronized boolean enter(){if(active||terminal)return false;active=true;return true;}
        private synchronized void leave(){active=false;notifyAll();}
        private synchronized boolean claimTerminal(){if(terminal)return false;terminal=true;return true;}
        private synchronized boolean awaitProducer(long until)throws InterruptedException{
            while(active){long left=until-System.nanoTime();if(left<=0)return false;TimeUnit.NANOSECONDS.timedWait(this,left);}
            return true;
        }
        private void terminate(HttpExchange exchange)throws IOException{
            if(!exchange.getRequestMethod().equals("POST")){reply(exchange,405,OwnedBillingFirstPaymentRuntime.error("INVALID_REQUEST"));return;}
            try{OwnedBillingFirstPaymentRuntime.fields(OwnedBillingFirstPaymentRuntime.Handler.read(exchange,false),Set.of());}
            catch(Exception malformed){reply(exchange,400,OwnedBillingFirstPaymentRuntime.error("INVALID_REQUEST"));return;}
            if(!claimTerminal()){reply(exchange,400,OwnedBillingFirstPaymentRuntime.error("FAILED_RENEWAL_STOPPED"));return;}
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25); // within the existing 30s runtime-close budget
            var drain=new FutureTask<Boolean>(()->{
                operations.abort();
                return awaitProducer(until)&&operations.awaitQuiescence(until);
            });
            Thread.ofVirtual().name("failed-renewal-terminal").start(drain);
            boolean proved=false;
            try{proved=drain.get(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(TimeoutException|ExecutionException unconfirmed){/* no terminal proof */}
            finally{drain.cancel(true);}
            reply(exchange,proved?200:400,OwnedBillingFirstPaymentRuntime.error(proved?"QUIESCENT":"FAILED_RENEWAL_STOPPED"));
        }
        @Override public void handle(HttpExchange exchange)throws IOException {
            boolean owned=false;
            try{
                var auth=exchange.getRequestHeaders().get("Authorization");
                if(auth==null||auth.size()!=1||!MessageDigest.isEqual(authorization,auth.getFirst().getBytes(StandardCharsets.UTF_8))){reply(exchange,403,OwnedBillingFirstPaymentRuntime.error("DENIED"));return;}
                String path=exchange.getRequestURI().toString();
                if(path.equals("/failed-renewal/abort")){terminate(exchange);return;}
                // Refusal carries no cleanup right. This also guards the original initial Handler.
                if(!enter()){reply(exchange,400,OwnedBillingFirstPaymentRuntime.error("FAILED_RENEWAL_STOPPED"));return;}
                owned=true;
                if(!PATHS.contains(path)){initial.handle(exchange);return;}
                if(!exchange.getRequestMethod().equals("POST")){operations.abort();reply(exchange,405,OwnedBillingFirstPaymentRuntime.error("INVALID_REQUEST"));return;}
                OwnedBillingFirstPaymentRuntime.fields(OwnedBillingFirstPaymentRuntime.Handler.read(exchange,false),Set.of());
                var response=switch(path){
                    case "/failed-renewal/boundary" -> operations.boundary();case "/failed-renewal/arm-decline" -> operations.armDecline();case "/failed-renewal/failed" -> operations.failedRenewal();
                    case "/failed-renewal/grace-last-second" -> operations.graceLastSecond();case "/failed-renewal/grace-at" -> operations.graceAt();case "/failed-renewal/grace-after" -> operations.graceAfter();
                    case "/failed-renewal/method/prepare" -> operations.prepareMethod();case "/failed-renewal/method/select" -> operations.selectMethod();case "/failed-renewal/method/confirm" -> operations.confirmMethod();case "/failed-renewal/method/apply" -> operations.applyMethod();
                    case "/failed-renewal/recovery/confirm" -> operations.confirmRecovery();case "/failed-renewal/recovery/observe" -> operations.observeRecovery();default -> throw new IllegalStateException();
                };
                reply(exchange,200,response);
            }catch(Exception invalid){
                if(!owned)throw invalid instanceof IOException io?io:new IOException("FAILED_RENEWAL_STOPPED");
                String stage;try{stage=operations.failureStage();}catch(RuntimeException unavailable){stage="UNCLASSIFIED";}
                var response=OwnedBillingFirstPaymentRuntime.error("FAILED_RENEWAL_STOPPED");response.addProperty("stage",stage!=null&&STAGES.contains(stage)?stage:"UNCLASSIFIED");
                operations.abort();reply(exchange,400,response);
            }finally{if(owned)leave();exchange.close();}
        }
        private static void reply(HttpExchange exchange,int code,JsonObject body)throws IOException{OwnedBillingFirstPaymentRuntime.Handler.reply(exchange,code,body);}
    }
    /** Ordinary application calls retain their original transport. A private producer
     * binds its immutable ticket before any SDK client or worker can be created. */
    static class ScopedClients extends dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory {
        private static final InheritableThreadLocal<UnaryOperator<HttpClient>> CURRENT=new InheritableThreadLocal<>();
        private final Supplier<HttpClient> injected;
        ScopedClients(){injected=null;}
        ScopedClients(Supplier<HttpClient> injected){this.injected=Objects.requireNonNull(injected);}
        static void bind(OwnedBillingFailedRenewalSandboxScenario.State state,long ticket){
            CURRENT.set(delegate->new GuardedTransport(state,ticket,delegate));
        }
        static void unbind(){CURRENT.remove();}
        @Override protected HttpClient transport(){
            HttpClient delegate=injected==null?super.transport():injected.get();
            var captured=CURRENT.get();return captured==null?delegate:captured.apply(delegate);
        }
    }
    private static final class GuardedTransport extends HttpClient{
        private final OwnedBillingFailedRenewalSandboxScenario.State state;
        private final long ticket;private final HttpClient delegate;
        GuardedTransport(OwnedBillingFailedRenewalSandboxScenario.State state,long ticket,HttpClient delegate){
            this.state=state;this.ticket=ticket;this.delegate=Objects.requireNonNull(delegate);
        }
        @Override public StripeResponse request(StripeRequest request)throws StripeException{
            state.beginTransport(ticket);
            try{return delegate.request(request);}finally{state.endTransport();}
        }
    }
    @Configuration(proxyBeanMethods=false)
    public static class BillingTimeConfiguration {
        @Bean @Primary ScopedClients failedRenewalStripeClients(){return new ScopedClients();}
        @Bean @Primary OwnedFailedGraceBillingTime ownedFailedGraceBillingTime(JdbcTemplate jdbc,EntityManagerFactory emf){
            return new OwnedFailedGraceBillingTime(jdbc,emf,new OwnedFailedRenewalBillingTime(jdbc,new OwnedFirstRenewalBillingTime(jdbc,new OwnedSubscriptionBillingTime(jdbc))));
        }
    }
    public static void main(String[] args)throws Exception {
        var resources=admitted(args,System.getenv(),OwnedBillingFirstPaymentRuntime.StartupResources::new);var admission=resources.admission;var deadline=Instant.now().plus(Duration.ofMinutes(45));
        var context=resources.acquireContext(()->{System.setProperty("payment.subscriptions.scheduling-enabled","false");var app=new SpringApplication(PaymentPlatformApplication.class,BillingTimeConfiguration.class);app.setAdditionalProfiles("local");return app.run();},org.springframework.context.ConfigurableApplicationContext::close);
        resources.run(()->{context.addApplicationListener(event->{if(event instanceof org.springframework.context.event.ContextClosedEvent closing&&closing.getApplicationContext()==context)resources.contextClosed();});resources.checkContextAfterListenerRegistration(context.isClosed(),context.isActive());});
        var scenario=resources.acquire(()->new OwnedBillingFailedRenewalSandboxScenario(admission,context::getBean,Clock.systemUTC(),deadline),OwnedBillingFailedRenewalSandboxScenario::abort);
        var server=resources.acquire(()->HttpServer.create(new InetSocketAddress(admission.bind(),admission.port()),4),s->s.stop(0));
        var executor=resources.acquire(Executors::newVirtualThreadPerTaskExecutor,ExecutorService::shutdownNow);
        resources.run(()->server.setExecutor(executor));resources.run(()->server.createContext("/",new Handler(scenario,admission.capability())));resources.run(server::start);
    }
}
