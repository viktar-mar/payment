package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.exception.ApiConnectionException;
import com.stripe.net.HttpClient;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
        try {
            var response = transport.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new ApiConnectionException("Stripe redirect refused");
            }
            return new StripeResponse(response.statusCode(), HttpHeaders.of(response.headers().map()), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiConnectionException("Stripe request interrupted");
        } catch (IOException failure) {
            throw new ApiConnectionException("Stripe request unavailable");
        }
    }
}
