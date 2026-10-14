package dev.genericpayments.platform.webhooks.adapter.out.http;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
class SafeCallbackHttpAdapterTest {
    @Test void productionRejectsPrivateResolutionAndLocalHttp() throws Exception {
        var policy=new CallbackUrlPolicy(false,host -> List.of(InetAddress.getByName("127.0.0.1")));
        assertThatThrownBy(() -> policy.validate("https://callback.example/path")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> policy.validate("http://localhost:8081/path")).isInstanceOf(RuntimeException.class);
    }
    @Test void onlyExactLocalNamesMayUseRelaxedPolicy() throws Exception {
        var policy=new CallbackUrlPolicy(true,host -> List.of(InetAddress.getByName("127.0.0.1")));
        assertThat(policy.validate("http://localhost:8081/path?q=1").toString()).isEqualTo("http://localhost:8081/path?q=1");
        assertThatThrownBy(() -> policy.validate("http://localhost.evil:8081/path")).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"0.0.0.0","10.0.0.1","127.0.0.1","169.254.169.254","172.16.0.1","192.168.1.1","100.64.0.1","198.18.0.1","224.0.0.1","240.0.0.1","::","::1","fe80::1","ff02::1","fd00:ec2::254","::ffff:127.0.0.1","::ffff:169.254.169.254","2001:db8::1"})
    void rejectsEveryForbiddenAddress(String address)throws Exception {
        var policy=new CallbackUrlPolicy(false,host -> List.of(InetAddress.getByName(address)));
        assertThatThrownBy(()->policy.validate("https://example.com/callback")).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"http://example.com/path","https://u:p@example.com/path","https://example.com/#fragment","https://example.com:8443/path","//example.com/path","https:///path","https://example.com/\npath","http://localhost:9090/path","http://localhost.evil:8081/path"})
    void rejectsMalformedOrDisallowedUris(String url)throws Exception {
        var policy=new CallbackUrlPolicy(false,host -> List.of(InetAddress.getByName("8.8.8.8")));
        assertThatThrownBy(()->policy.validate(url)).isInstanceOf(RuntimeException.class);
    }
    @Test void anyForbiddenAddressRejectsMixedAnswerAndResolutionRunsOnEveryPost()throws Exception {
        int[] calls={0};var policy=new CallbackUrlPolicy(false,host->{calls[0]++;return List.of(InetAddress.getByName(calls[0]==1?"8.8.8.8":"127.0.0.1"));});
        var sent=new ArrayList<HttpRequest>();var adapter=new SafeCallbackHttpAdapter(policy,(request,handler)->{sent.add(request);return 204;},java.time.Duration.ofSeconds(2));
        byte[] body="raw".getBytes(java.nio.charset.StandardCharsets.UTF_8);var headers=headers();
        assertThat(adapter.post("https://example.com/callback",headers,body).succeeded()).isTrue();
        assertThat(adapter.post("https://example.com/callback",headers,body).error()).isEqualTo(CallbackHttpPort.ErrorCategory.POLICY);
        assertThat(sent).hasSize(1);assertThat(sent.getFirst().method()).isEqualTo("POST");assertThat(sent.getFirst().headers().map()).hasSize(5);assertThat(sent.getFirst().timeout()).contains(java.time.Duration.ofSeconds(2));
        var mixed=new CallbackUrlPolicy(false,host->List.of(InetAddress.getByName("8.8.8.8"),InetAddress.getByName("10.0.0.1")));
        assertThatThrownBy(()->mixed.validate("https://example.com")).isInstanceOf(RuntimeException.class);
    }
    @Test void statusAndTransportFailuresAreFiniteAndResponseBodyIsDiscardedWithHardCap()throws Exception {
        var policy=new CallbackUrlPolicy(false,host->List.of(InetAddress.getByName("8.8.8.8")));
        for(int status:new int[]{200,204,299,301,302,400,500}) {
            var adapter=new SafeCallbackHttpAdapter(policy,(request,handler)->status,java.time.Duration.ofSeconds(2));var result=adapter.post("https://example.com",headers(),new byte[]{1});
            assertThat(result.succeeded()).isEqualTo(status>=200&&status<300);assertThat(result.httpStatus()).isEqualTo(status);
        }
        var timeout=new SafeCallbackHttpAdapter(policy,(request,handler)->{throw new HttpTimeoutException("private body");},java.time.Duration.ofSeconds(2));
        assertThat(timeout.post("https://example.com",headers(),new byte[]{1}).error()).isEqualTo(CallbackHttpPort.ErrorCategory.TIMEOUT);
        var tls=new SafeCallbackHttpAdapter(policy,(request,handler)->{throw new javax.net.ssl.SSLException("private body");},java.time.Duration.ofSeconds(2));
        assertThat(tls.post("https://example.com",headers(),new byte[]{1}).error()).isEqualTo(CallbackHttpPort.ErrorCategory.TLS);
        var network=new SafeCallbackHttpAdapter(policy,(request,handler)->{throw new java.io.IOException("private body");},java.time.Duration.ofSeconds(2));
        assertThat(network.post("https://example.com",headers(),new byte[]{1}).error()).isEqualTo(CallbackHttpPort.ErrorCategory.NETWORK);
        var subscriber=new SafeCallbackHttpAdapter.DiscardingSubscriber();boolean[] cancelled={false};subscriber.onSubscribe(new Flow.Subscription(){public void request(long count){}public void cancel(){cancelled[0]=true;}});
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[8192])));assertThat(cancelled[0]).isFalse();subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertThat(cancelled[0]).isTrue();assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
        var small=new SafeCallbackHttpAdapter.DiscardingSubscriber();small.onSubscribe(new Flow.Subscription(){public void request(long count){}public void cancel(){}});small.onNext(List.of(ByteBuffer.wrap(new byte[10])));small.onComplete();assertThat(small.getBody().toCompletableFuture().join()).isNull();
    }
    static Map<String,String> headers(){return Map.of("Payment-Event-Id","10000000-0000-0000-0000-000000000001","Payment-Timestamp","1720000000","Payment-Signature","v1="+"a".repeat(64),"Payment-Secret-Version","1","Content-Type","application/json");}
    @Test void normalizationPreservesEncodedPathAndQueryAndRejectsEncodedControlCharacters()throws Exception {
        var policy=new CallbackUrlPolicy(false,host->List.of(InetAddress.getByName("8.8.8.8")));
        assertThat(policy.validate("HTTPS://EXAMPLE.COM/a%2Fb?q=x%26y").toASCIIString()).isEqualTo("https://example.com/a%2Fb?q=x%26y");
        assertThatThrownBy(()->policy.validate("https://example.com/path?x=%0a")).isInstanceOf(RuntimeException.class);
    }
    @Test void dnsFailureHasSafeDedicatedCategoryAndForbiddenHeadersAreNeverForwarded() {
        var policy=new CallbackUrlPolicy(false,host->{throw new UnknownHostException("private DNS contents");});
        var adapter=new SafeCallbackHttpAdapter(policy,(request,handler)->{throw new AssertionError("DNS failure sent");},java.time.Duration.ofSeconds(2));
        assertThat(adapter.post("https://example.com",headers(),new byte[0]).error()).isEqualTo(CallbackHttpPort.ErrorCategory.DNS);
        var extra=new HashMap<>(headers());extra.put("Authorization","private-value");
        assertThat(adapter.post("https://example.com",extra,new byte[0]).error()).isEqualTo(CallbackHttpPort.ErrorCategory.POLICY);
    }
    @Test void localPolicyAllowsExactlyThreeNamesAndFourHttpPorts()throws Exception {
        var policy=new CallbackUrlPolicy(true,host->List.of(InetAddress.getByName("10.0.0.1")));
        for(String host:List.of("localhost","127.0.0.1","callback-receiver"))for(int port:new int[]{80,3000,8080,8081})assertThat(policy.validate("http://"+host+":"+port+"/callback")).isNotNull();
        assertThatThrownBy(()->policy.validate("http://callback-receiver.:8081/path")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->policy.validate("http://10.0.0.1:8081/path")).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"2001::1","2001:2::1","2001:10::1","2002:7f00:1::1"})
    void rejectsReservedTransitionIpv6Ranges(String address)throws Exception {
        var policy=new CallbackUrlPolicy(false,host->List.of(InetAddress.getByName(address)));
        assertThatThrownBy(()->policy.validate("https://example.com")).isInstanceOf(RuntimeException.class);
    }
    @Test void bodyLimitFailureMapsSafelyAndTimeoutConfigurationIsBounded()throws Exception {
        var policy=new CallbackUrlPolicy(false,host->List.of(InetAddress.getByName("8.8.8.8")));
        var adapter=new SafeCallbackHttpAdapter(policy,(request,handler)->{throw new java.io.IOException("private",new SafeCallbackHttpAdapter.BodyLimitException());},java.time.Duration.ofSeconds(2));
        assertThat(adapter.post("https://example.com",headers(),new byte[0]).error()).isEqualTo(CallbackHttpPort.ErrorCategory.BODY_LIMIT);
        assertThatThrownBy(()->SafeCallbackHttpAdapter.create(policy,java.time.Duration.ofSeconds(11),java.time.Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SafeCallbackHttpAdapter.create(policy,java.time.Duration.ofSeconds(1),java.time.Duration.ofSeconds(31))).isInstanceOf(IllegalArgumentException.class);
    }
}
