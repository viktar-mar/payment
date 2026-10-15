package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.ProductCreateParams;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StripeClientFactoryTest {
    @Test void instanceClientsSendTheirOwnKeysAndTheSdkVersionPin() throws Exception {
        try (var http = new StripeHttpFixture()) {
            http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_first\",\"object\":\"account\"}");
            http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_second\",\"object\":\"account\"}");
            var factory = fixtureFactory(http.baseUri());
            var first = factory.create(credentials("A"));
            var second = factory.create(credentials("D"));
            assertThat(first.v1().accounts().retrieveCurrent().getId()).isEqualTo("acct_first");
            assertThat(second.v1().accounts().retrieveCurrent().getId()).isEqualTo("acct_second");
            assertThat(http.calls()).hasSize(2);
            assertThat(http.calls().get(0).header("Authorization")).isEqualTo("Bearer sk_test_" + "A".repeat(32));
            assertThat(http.calls().get(1).header("Authorization")).isEqualTo("Bearer sk_test_" + "D".repeat(32));
            assertThat(http.calls()).allSatisfy(call -> {
                assertThat(call.header("Stripe-Version")).isEqualTo("2026-09-30.endive");
                assertThat(call.header("Stripe-Account")).isNull();
                assertThat(call.method()).isEqualTo("GET");
                assertThat(call.toString()).isEqualTo("StripeHttpFixture.Call[redacted]");
            });
        }
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 303, 307, 308})
    void refusesRedirectWithoutSendingAuthorizationToAnotherRequest(int status) throws Exception {
        try (var http = new StripeHttpFixture(); var destination = new StripeHttpFixture()) {
            http.enqueue("GET", "/v1/account", status, "{}",
                Map.of("Location", destination.baseUri().resolve("/stolen").toString()), 0);
            assertThatThrownBy(() -> fixtureFactory(http.baseUri()).create(credentials("A"))
                .v1().accounts().retrieveCurrent()).isInstanceOf(StripeException.class);
            assertThat(http.calls()).hasSize(1);
            assertThat(destination.calls()).isEmpty();
        }
    }

    @Test void serverFailureDoesNotRetry() throws Exception {
        try (var http = new StripeHttpFixture()) {
            http.enqueue("GET", "/v1/account", 500, "{\"error\":{\"type\":\"api_error\",\"message\":\"unavailable\"}}");
            assertThatThrownBy(() -> fixtureFactory(http.baseUri()).create(credentials("A"))
                .v1().accounts().retrieveCurrent()).isInstanceOf(StripeException.class);
            assertThat(http.calls()).hasSize(1);
        }
    }

    @Test void sdkFormAndIdempotencyHeadersSurviveTransport() throws Exception {
        try (var http = new StripeHttpFixture()) {
            http.enqueue("POST", "/v1/products", 200, "{\"id\":\"prod_fixture\",\"object\":\"product\"}");
            var product = fixtureFactory(http.baseUri()).create(credentials("A")).v1().products().create(
                ProductCreateParams.builder().setName("Fixture product & details").putMetadata("local_id", "resource-123").build(),
                RequestOptions.builder().setIdempotencyKey("fixture-product-operation").build());
            assertThat(product.getId()).isEqualTo("prod_fixture");
            assertThat(http.calls()).singleElement().satisfies(call -> {
                assertThat(call.header("Idempotency-Key")).isEqualTo("fixture-product-operation");
                assertThat(call.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
                assertThat(call.form()).containsEntry("name", "Fixture product & details")
                    .containsEntry("metadata[local_id]", "resource-123");
            });
        }
    }

    @Test void disconnectedPostIsNotReplayedByTheTransport() throws Exception {
        try (var http = new StripeHttpFixture()) {
            http.disconnectNext("POST", "/v1/products");
            assertThatThrownBy(() -> fixtureFactory(http.baseUri()).create(credentials("A")).v1().products().create(
                ProductCreateParams.builder().setName("Fixture product").build(),
                RequestOptions.builder().setIdempotencyKey("fixture-disconnect-operation").build()))
                .isInstanceOf(StripeException.class);
            assertThat(http.calls()).hasSize(1);
        }
    }

    static StripeCredentials credentials(String secretCharacter) {
        return new StripeCredentials("sk_test_" + secretCharacter.repeat(32),
            StripeSandboxConfigurationTest.PUBLIC, StripeSandboxConfigurationTest.WEBHOOK);
    }

    static StripeClientFactory fixtureFactory(URI baseUri) {
        return new StripeClientFactory() {
            @Override protected StripeClient.StripeClientBuilder builder(StripeCredentials credentials) {
                return super.builder(credentials).setApiBase(baseUri.toString());
            }
        };
    }
}
