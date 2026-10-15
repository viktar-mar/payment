package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.exception.ApiConnectionException;
import com.stripe.net.HttpClient;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** SDK transport with redirects disabled so an upstream redirect cannot forward credentials. */
final class StripeSdkHttpClient extends HttpClient {
    private final java.net.http.HttpClient transport = java.net.http.HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(5_000)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();

    @Override public StripeResponse request(StripeRequest request) throws ApiConnectionException {
        var builder = HttpRequest.newBuilder(URI.create(request.url().toString()))
            .timeout(Duration.ofMillis(request.options().getReadTimeout()));
        request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        builder.header("User-Agent", buildUserAgentString(request));
        builder.header("X-Stripe-Client-User-Agent", buildXStripeClientUserAgentString());
        var content = request.content();
        if (content != null) builder.header("Content-Type", content.contentType());
        builder.method(request.method().name(), content == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(content.byteArrayContent()));
        // JDK request timeout alone can finish at headers. Bound the entire response body and any
        // transparent JDK GET retry with one deadline; cancellation also releases the owned exchange.
        var pending = transport.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
        try {
            var response = pending.get(request.options().getReadTimeout(), TimeUnit.MILLISECONDS);
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new ApiConnectionException("Stripe redirect refused");
            }
            return new StripeResponse(response.statusCode(), HttpHeaders.of(response.headers().map()), response.body());
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiConnectionException("Stripe request interrupted");
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new ApiConnectionException("Stripe request timed out");
        } catch (ExecutionException failure) {
            throw new ApiConnectionException("Stripe request unavailable");
        }
    }
}
