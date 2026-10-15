package dev.genericpayments.platform.provider.adapter.in.configuration;

import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class StripeSandboxConfiguration {
    @Bean StripeSandboxProperties stripeSandboxProperties(Environment environment) {
        String enabledValue = property(environment, "enabled", "ENABLED", "false");
        if (!Set.of("true", "false").contains(enabledValue)) {
            throw new IllegalArgumentException("Stripe Sandbox enabled setting is invalid");
        }
        boolean enabled = Boolean.parseBoolean(enabledValue);
        if (enabled) {
            var profiles = Arrays.asList(environment.getActiveProfiles());
            if (profiles.stream().noneMatch(Set.of("local", "test")::contains)
                    || profiles.stream().anyMatch(profile -> !Set.of("local", "test", "stripe-sandbox").contains(profile))) {
                throw new IllegalArgumentException("Stripe Sandbox requires only local or test profiles");
            }
        }
        return new StripeSandboxProperties(enabled,
            property(environment, "secret-key", "SECRET_KEY", ""),
            property(environment, "publishable-key", "PUBLISHABLE_KEY", ""),
            property(environment, "webhook-secret", "WEBHOOK_SECRET", ""));
    }

    private static String property(Environment environment, String property, String variable, String fallback) {
        return environment.getProperty("payment.stripe.sandbox." + property,
            environment.getProperty("PAYMENT_STRIPE_SANDBOX_" + variable, fallback));
    }

    @Bean StripeCredentialResolver stripeCredentialResolver(StripeSandboxProperties properties) {
        return new StripeCredentialResolver(properties);
    }

    @Bean @ConditionalOnMissingBean(StripeClientFactory.class)
    StripeClientFactory stripeClientFactory() { return new StripeClientFactory(); }

    @Bean PaymentAccountVerificationPort paymentAccountVerificationPort(StripeCredentialResolver credentials,
                                                                      StripeClientFactory clients) {
        return new StripeAccountVerificationAdapter(credentials, clients);
    }
}
