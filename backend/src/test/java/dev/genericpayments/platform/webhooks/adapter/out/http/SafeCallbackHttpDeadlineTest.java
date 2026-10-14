package dev.genericpayments.platform.webhooks.adapter.out.http;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort.ErrorCategory;
import java.net.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SafeCallbackHttpDeadlineTest {
    @ParameterizedTest @ValueSource(strings={"delayed","never","trickle"})
    void bodyCompletionIsBoundedAndNextRequestStillWorks(String mode) throws Exception {
        var release=new CountDownLatch(1);var headersSent=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),8081),0);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/slow",exchange->{
                try(exchange){
                    exchange.sendResponseHeaders(200,0);exchange.getResponseBody().flush();headersSent.countDown();
                    if(mode.equals("delayed"))Thread.sleep(2500);
                    else if(mode.equals("never"))release.await();
                    else for(int i=0;i<10;i++){exchange.getResponseBody().write('x');exchange.getResponseBody().flush();Thread.sleep(250);}
                    exchange.getResponseBody().write("opaque response".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(java.io.IOException cancelled){/* client has cancelled */}
            });
            server.createContext("/fast",exchange->{try(exchange){exchange.sendResponseHeaders(204,-1);}});
            server.start();
            try {
                var adapter=client();
                for(int attempt=0;attempt<2;attempt++){
                    // The test itself has a fail-fast guard; a regressed client cannot
                    // hang the suite on the body that only finally's release completes.
                    long start=System.nanoTime();var call=executor.submit(()->adapter.post("http://localhost:8081/slow",SafeCallbackHttpAdapterTest.headers(),new byte[]{1}));
                    var result=call.get(2,TimeUnit.SECONDS);
                    long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                    assertThat(headersSent.getCount()).isZero();
                    assertThat(result.error()).isEqualTo(ErrorCategory.TIMEOUT);assertThat(result.httpStatus()).isNull();
                    assertThat(result.toString()).doesNotContain("opaque response");
                    assertThat(elapsed).isBetween(250L,2000L);
                    assertThat(adapter.post("http://localhost:8081/fast",SafeCallbackHttpAdapterTest.headers(),new byte[]{1}).succeeded()).isTrue();
                }
            }finally{release.countDown();server.stop(0);}
        }finally{server.stop(0);}
    }

    @Test void realClientRetainsRedirectBodyCapAndHeaderDeadline() throws Exception {
        var targetCalls=new AtomicInteger();var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),8081),0);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            server.setExecutor(executor);
            server.createContext("/redirect",exchange->{try(exchange){exchange.getResponseHeaders().set("Location","/target");exchange.sendResponseHeaders(302,-1);}});
            server.createContext("/target",exchange->{try(exchange){targetCalls.incrementAndGet();exchange.sendResponseHeaders(204,-1);}});
            server.createContext("/large",exchange->{try(exchange){exchange.sendResponseHeaders(200,8193);exchange.getResponseBody().write(new byte[8193]);}});
            server.createContext("/headers",exchange->{try(exchange){release.await(3,TimeUnit.SECONDS);exchange.sendResponseHeaders(204,-1);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            server.start();
            try{
                var adapter=client();var headers=SafeCallbackHttpAdapterTest.headers();
                assertThat(adapter.post("http://localhost:8081/redirect",headers,new byte[0]).error()).isEqualTo(ErrorCategory.HTTP_STATUS);assertThat(targetCalls).hasValue(0);
                assertThat(adapter.post("http://localhost:8081/large",headers,new byte[0]).error()).isEqualTo(ErrorCategory.BODY_LIMIT);
                long start=System.nanoTime();assertThat(adapter.post("http://localhost:8081/headers",headers,new byte[0]).error()).isEqualTo(ErrorCategory.TIMEOUT);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(2000);
            }finally{release.countDown();server.stop(0);}
        }finally{server.stop(0);}
    }
    private static SafeCallbackHttpAdapter client(){return SafeCallbackHttpAdapter.create(new CallbackUrlPolicy(true,host->List.of(InetAddress.getLoopbackAddress())),Duration.ofMillis(500),Duration.ofMillis(500));}
}
