package dev.genericpayments.acceptance.runtime;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.io.IOException;
import com.sun.net.httpserver.HttpServer;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.webhooks.application.port.out.OutboxPort;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;

/** Explicit test launcher support; never included in the production artifact or component scan. */
public final class OwnedSubscriptionRuntime {
    private OwnedSubscriptionRuntime() {}
    private static final Set<String> BUSINESS_BEANS=Set.of(
        "subscriptionCommandTransaction","subscriptionFactService","subscriptionQueryService",
        "subscriptionPlanTransaction","subscriptionCancellationTransaction","subscriptionRecoveryTransaction",
        "subscriptionInteractionTransaction","subscriptionQuotePersistenceAdapter","subscriptionActionPersistenceAdapter",
        "subscriptionSweepPersistenceAdapter","subscriptionSweepWorker","subscriptionDeadlineWorker",
        "subscriptionReconciliationService","subscriptionFakeControlService","fakeSubscriptionState",
        "fakeSubscriptionQueueRelay","subscriptionInboxWorker","outboxWorker","callbackRetryService");

    public static DefaultListableBeanFactory factory(Clock business) {
        return new DefaultListableBeanFactory() {
            @Override public Object resolveDependency(DependencyDescriptor d,String bean,Set<String> names,TypeConverter converter) {
                if(d.getDependencyType()==Clock.class&&bean!=null&&BUSINESS_BEANS.contains(bean))return business;
                Object resolved=super.resolveDependency(d,bean,names,converter);
                if(d.getDependencyType()==OutboxPort.class&&"callbackDeliveryService".equals(bean))
                    return completionClock((OutboxPort)resolved,business);
                return resolved;
            }
        };
    }

    /** Delivery signs in real UTC; outbox claim/lease/completion/retry all use owned business time. */
    public static OutboxPort completionClock(OutboxPort delegate,Clock business) {
        return (OutboxPort)java.lang.reflect.Proxy.newProxyInstance(OutboxPort.class.getClassLoader(),new Class<?>[]{OutboxPort.class},(proxy,method,args)->{
            if(method.getName().equals("complete")){args=args.clone();args[2]=business.instant();}
            try{return method.invoke(delegate,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }

    public static final class BusinessClock extends Clock {
        private final AtomicReference<Instant> now;
        private final Path state;
        public BusinessClock(Instant at){this(at,null);}
        private BusinessClock(Instant at,Path state){now=new AtomicReference<>(at);this.state=state;}
        public static BusinessClock persisted(Instant initial,Path state) {
            try {
                if(!state.isAbsolute()||!Files.isDirectory(state.getParent(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(state))throw new IOException();
                Instant saved=initial;
                if(Files.exists(state,LinkOption.NOFOLLOW_LINKS)){
                    if(!Files.isRegularFile(state,LinkOption.NOFOLLOW_LINKS)||Files.size(state)>64)throw new IOException();
                    saved=Instant.parse(Files.readString(state,StandardCharsets.UTF_8));
                }
                var clock=new BusinessClock(saved,state);clock.persist(saved);return clock;
            }catch(IOException|RuntimeException invalid){throw new IllegalStateException("Invalid owned time state");}
        }
        private void persist(Instant at) {
            if(state==null)return;
            Path temporary=null;
            try {
                if(Files.isSymbolicLink(state))throw new IOException();
                temporary=Files.createTempFile(state.getParent(),".owned-time-",".tmp");
                try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){
                    var bytes=ByteBuffer.wrap(at.toString().getBytes(StandardCharsets.UTF_8));
                    while(bytes.hasRemaining())channel.write(bytes);
                    channel.force(true);
                }
                try(var directory=FileChannel.open(state.getParent(),StandardOpenOption.READ)){
                    directory.force(true);
                    Files.move(temporary,state,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    directory.force(true);
                }
            }catch(IOException failure){throw new IllegalStateException("Owned time persistence failed");}
            finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){/* only a time-only owned temporary file */}}
        }
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException("UTC required");return this;}
        @Override public Instant instant(){return now.get();}
        public synchronized void advance(Instant at){
            if(at.isBefore(now.get())||at.isAfter(now.get().plus(Duration.ofDays(366))))throw new IllegalArgumentException("Invalid owned time");
            persist(at);now.set(at);
        }
    }

    static void requireOptIn(Map<String,String> env) {
        if(!"true".equals(env.get("PAYMENT_STAGE4_TEST_RUNTIME"))
            ||!env.getOrDefault("PAYMENT_STAGE4_CONTROL_KEY","").matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Explicit owned runtime opt-in required");
    }

    /** Separate private test channel, never registered as an application/public route. */
    public static HttpServer control(BusinessClock clock,byte[] capability,InetSocketAddress bind) throws Exception {
        var server=HttpServer.create(bind,4);
        server.createContext("/time",exchange->{
            try {
                if(!exchange.getRequestURI().toString().equals("/time")){exchange.sendResponseHeaders(404,-1);return;}
                var supplied=exchange.getRequestHeaders().getFirst("Authorization");
                if(supplied==null||!MessageDigest.isEqual(("Bearer "+new String(capability,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))){exchange.sendResponseHeaders(403,-1);return;}
                if(exchange.getRequestMethod().equals("POST")){
                    byte[] bytes;
                    try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                        var read=executor.submit(()->exchange.getRequestBody().readNBytes(65));
                        try{bytes=read.get(5,java.util.concurrent.TimeUnit.SECONDS);}
                        catch(Exception failed){exchange.getRequestBody().close();read.cancel(true);exchange.sendResponseHeaders(400,-1);return;}
                    }
                    if(bytes.length>64){exchange.sendResponseHeaders(413,-1);return;}
                    clock.advance(Instant.parse(new String(bytes,StandardCharsets.UTF_8)));
                } else if(!exchange.getRequestMethod().equals("GET")){exchange.sendResponseHeaders(405,-1);return;}
                byte[] body=clock.instant().toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(RuntimeException invalid){exchange.sendResponseHeaders(400,-1);}
            finally{exchange.close();}
        });
        server.start();return server;
    }

    public static void main(String[] args) throws Exception {
        var env=System.getenv();requireOptIn(env);
        var bind=env.getOrDefault("PAYMENT_STAGE4_CONTROL_BIND","127.0.0.1");
        if(!Set.of("127.0.0.1","0.0.0.0").contains(bind))throw new IllegalArgumentException("Invalid owned binding");
        int port=Integer.parseInt(env.get("PAYMENT_STAGE4_CONTROL_PORT"));
        if(port<1024||port>65535)throw new IllegalArgumentException("Invalid owned port");
        var state=Path.of(env.getOrDefault("PAYMENT_STAGE4_TIME_FILE",""));
        if(!state.isAbsolute()||!state.getFileName().toString().equals("instant.txt")||!state.getParent().getFileName().toString().startsWith("task17-time-"))throw new IllegalArgumentException("Dedicated owned time path required");
        var business=BusinessClock.persisted(Instant.now(),state);
        var app=new SpringApplication(PaymentPlatformApplication.class);
        app.setApplicationContextFactory(type->new AnnotationConfigServletWebServerApplicationContext(factory(business)));
        app.setAdditionalProfiles("local");
        var context=app.run(args);
        var server=control(business,env.get("PAYMENT_STAGE4_CONTROL_KEY").getBytes(StandardCharsets.UTF_8),new InetSocketAddress(bind,port));
        context.addApplicationListener(event->{if(event instanceof org.springframework.context.event.ContextClosedEvent)server.stop(0);});
    }
}
