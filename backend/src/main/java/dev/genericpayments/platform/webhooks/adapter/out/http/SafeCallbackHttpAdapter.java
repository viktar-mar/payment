package dev.genericpayments.platform.webhooks.adapter.out.http;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.net.http.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
public final class SafeCallbackHttpAdapter implements CallbackHttpPort {
    @FunctionalInterface public interface Transport {int send(HttpRequest request,HttpResponse.BodyHandler<Void> handler)throws IOException,InterruptedException;}
    private static final Set<String> HEADERS=Set.of("Payment-Event-Id","Payment-Timestamp","Payment-Signature","Payment-Secret-Version","Content-Type");
    private final CallbackUrlPolicy policy;private final Transport transport;private final Duration timeout;
    public SafeCallbackHttpAdapter(CallbackUrlPolicy policy,Transport transport,Duration timeout){if(timeout==null||timeout.compareTo(Duration.ofMillis(100))<0||timeout.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("Invalid callback timeout");this.policy=policy;this.transport=transport;this.timeout=timeout;}
    public static SafeCallbackHttpAdapter create(CallbackUrlPolicy policy,Duration connect,Duration request){if(connect==null||connect.compareTo(Duration.ofMillis(100))<0||connect.compareTo(Duration.ofSeconds(10))>0)throw new IllegalArgumentException("Invalid callback connect timeout");var client=HttpClient.newBuilder().connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).build();return new SafeCallbackHttpAdapter(policy,(r,h)->sendBounded(client,r,h),request);}
    private static int sendBounded(HttpClient client,HttpRequest request,HttpResponse.BodyHandler<Void> handler)throws IOException,InterruptedException {
        long start=System.nanoTime();long budget=request.timeout().orElseThrow().toNanos();
        var subscriber=new AtomicReference<DiscardingSubscriber>();var cancelled=new AtomicBoolean();
        var response=client.sendAsync(request,info->{
            var body=(DiscardingSubscriber)handler.apply(info);subscriber.set(body);
            if(cancelled.get())body.cancel();return body;
        });
        try {
            long remaining=budget-(System.nanoTime()-start);if(remaining<=0)throw new TimeoutException();
            return response.get(remaining,TimeUnit.NANOSECONDS).statusCode();
        }catch(TimeoutException expired){throw new HttpTimeoutException("Callback deadline exceeded");}
        catch(ExecutionException failure){if(failure.getCause() instanceof IOException io)throw io;throw new IOException("Callback transport failed",failure.getCause());}
        finally {
            // No timer or executor is created per send. Cancellation also covers the
            // race where the deadline expires just before headers create a subscriber.
            if(!response.isDone()){
                cancelled.set(true);var body=subscriber.get();if(body!=null)body.cancel();response.cancel(true);
            }
        }
    }
    @Override public Result post(String endpoint,Map<String,String> headers,byte[] body) {
        try{
            if(headers==null||!headers.keySet().equals(HEADERS)||!"application/json".equals(headers.get("Content-Type"))||!headers.get("Payment-Signature").matches("v1=[0-9a-f]{64}")||!headers.get("Payment-Timestamp").matches("[0-9]{1,20}")||!headers.get("Payment-Secret-Version").matches("[1-9][0-9]{0,9}")||body==null||body.length>16384)return Result.failed(null,ErrorCategory.POLICY);
            UUID.fromString(headers.get("Payment-Event-Id"));
            var request=HttpRequest.newBuilder(policy.validate(endpoint)).timeout(timeout).POST(HttpRequest.BodyPublishers.ofByteArray(body));headers.forEach(request::header);
            int status=transport.send(request.build(),info->new DiscardingSubscriber());return status>=200&&status<300?Result.success(status):Result.failed(status,ErrorCategory.HTTP_STATUS);
        }catch(CallbackUrlPolicy.ResolutionFailure failure){return Result.failed(null,ErrorCategory.DNS);}
        catch(PaymentApplicationException|IllegalArgumentException invalid){return Result.failed(null,ErrorCategory.POLICY);}
        catch(HttpTimeoutException timeout){return Result.failed(null,ErrorCategory.TIMEOUT);}
        catch(SSLException tls){return Result.failed(null,ErrorCategory.TLS);}
        catch(IOException failure){Throwable current=failure;for(int i=0;i<10&&current!=null;i++,current=current.getCause())if(current instanceof BodyLimitException)return Result.failed(null,ErrorCategory.BODY_LIMIT);return Result.failed(null,ErrorCategory.NETWORK);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Result.failed(null,ErrorCategory.NETWORK);}
        catch(RuntimeException failure){return Result.failed(null,ErrorCategory.NETWORK);}
    }
    static final class BodyLimitException extends IOException {BodyLimitException(){super("Callback response body limit exceeded");}}
    public static final class DiscardingSubscriber implements HttpResponse.BodySubscriber<Void> {
        private final CompletableFuture<Void> completed=new CompletableFuture<>();private Flow.Subscription subscription;private long received;
        @Override public CompletionStage<Void> getBody(){return completed;}
        @Override public synchronized void onSubscribe(Flow.Subscription subscription){if(this.subscription!=null||completed.isDone()){subscription.cancel();return;}this.subscription=subscription;subscription.request(1);}
        @Override public synchronized void onNext(List<ByteBuffer> buffers){if(completed.isDone())return;for(var buffer:buffers){received+=buffer.remaining();if(received>8192){subscription.cancel();completed.completeExceptionally(new BodyLimitException());return;}}subscription.request(1);}
        synchronized void cancel(){completed.completeExceptionally(new HttpTimeoutException("Callback deadline exceeded"));if(subscription!=null)subscription.cancel();}
        @Override public void onError(Throwable failure){completed.completeExceptionally(failure);}
        @Override public void onComplete(){completed.complete(null);}
    }
}
