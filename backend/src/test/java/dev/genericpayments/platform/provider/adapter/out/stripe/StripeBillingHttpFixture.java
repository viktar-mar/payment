package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.StripeClient;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.io.IOException;
import java.util.List;

/** Billing SDK boundary on an owned loopback server, using synthetic credentials only. */
final class StripeBillingHttpFixture implements AutoCloseable {
    private final StripeHttpFixture http = new StripeHttpFixture();
    private final StripeClient client;

    StripeBillingHttpFixture() throws IOException {
        client = StripeClientFactoryTest.fixtureFactory(http.baseUri())
            .create(StripeClientFactoryTest.credentials("A"));
    }

    StripeClient client() { return client; }

    void respond(String method, String path, String json) {
        http.enqueue(method, path, 200, json);
    }

    void respondError(String method,String path,int status,String json,java.util.Map<String,String> headers) {
        http.enqueue(method,path,status,json,headers,0);
    }
    void disconnectNext(String method, String path) { http.disconnectNext(method, path); }

    List<StripeHttpFixture.Call> calls() { return http.calls(); }

    long requestCount(String method, String path) {
        return http.calls().stream().filter(call -> call.method().equals(method) && call.path().equals(path)).count();
    }

    @Override public void close() { http.close(); }
}
