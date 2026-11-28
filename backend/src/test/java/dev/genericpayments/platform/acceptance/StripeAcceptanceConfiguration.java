package dev.genericpayments.platform.acceptance;

import com.stripe.StripeClient;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.config.BeanPostProcessor;
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
    private static final String BILLING_ACTION_KEY = randomKey();
    private static volatile Instant providerTime;
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("payment.stripe.sandbox.enabled", () -> true);
        registry.add("payment.stripe.sandbox.secret-key", () -> SERVER);
        registry.add("payment.stripe.sandbox.publishable-key", () -> PUBLIC);
        registry.add("payment.stripe.sandbox.webhook-secret", () -> SIGNING);
        registry.add("payment.stripe.billing-actions.enabled", () -> true);
        registry.add("payment.stripe.billing-actions.active-key-version", () -> "v1");
        registry.add("payment.stripe.billing-actions.key-versions", () -> "v1");
        registry.add("payment.stripe.billing-actions.keys.v1", () -> BILLING_ACTION_KEY);
    }
    static void providerTime(Instant instant) { providerTime = instant; }
    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
    @Bean static BeanPostProcessor fixtureProviderClock() {
        // Simulate an ordinary provider advancing with the loopback fixture, keeping UTC otherwise.
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!name.equals("providerReferenceClock")) return bean;
                var original = (java.time.Clock) bean;
                InstantSource time = () -> {
                    Instant simulated = providerTime;
                    return simulated == null ? original.instant() : simulated;
                };
                return time.withZone(ZoneOffset.UTC);
            }
        };
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
