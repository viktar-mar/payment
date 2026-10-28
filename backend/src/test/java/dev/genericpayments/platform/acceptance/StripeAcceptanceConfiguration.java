package dev.genericpayments.platform.acceptance;

import com.stripe.StripeClient;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cucumber-only, generated synthetic credentials; no provider URL can leave loopback. */
@TestConfiguration(proxyBeanMethods = false)
public class StripeAcceptanceConfiguration {
    private static final String SERVER = "sk_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PUBLIC = "pk_test_" + UUID.randomUUID().toString().replace("-", "");
    static final String SIGNING = "whsec_" + UUID.randomUUID().toString().replace("-", "");
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("payment.stripe.sandbox.enabled", () -> true);
        registry.add("payment.stripe.sandbox.secret-key", () -> SERVER);
        registry.add("payment.stripe.sandbox.publishable-key", () -> PUBLIC);
        registry.add("payment.stripe.sandbox.webhook-secret", () -> SIGNING);
    }
    @Bean @Primary FixtureFactory stripeAcceptanceFactory() { return new FixtureFactory(); }
    static final class FixtureFactory extends StripeClientFactory {
        private StripeHttpFixture fixture;
        synchronized void attach(StripeHttpFixture next) {
            if (fixture != null) throw new IllegalStateException("Acceptance fixture still owned");
            fixture = next;
        }
        synchronized void detach() { fixture = null; }
        @Override protected synchronized StripeClient.StripeClientBuilder builder(StripeCredentials credentials) {
            if (fixture == null || TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("Acceptance provider boundary unavailable");
            return super.builder(credentials).setApiBase(fixture.baseUri().toString()).setReadTimeout(1500);
        }
    }
}
