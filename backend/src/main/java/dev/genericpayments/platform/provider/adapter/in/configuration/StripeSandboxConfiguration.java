package dev.genericpayments.platform.provider.adapter.in.configuration;

import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.payments.application.port.out.LoadPaymentPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class StripeSandboxConfiguration {
    @Bean(name="providerReferenceClock",defaultCandidate=false)
    java.time.Clock providerReferenceClock() {return java.time.Clock.systemUTC();}

    @Bean StripeWebhookVerifier stripeWebhookVerifier(StripeCredentialResolver credentials) {
        return new StripeWebhookVerifier(credentials);
    }
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

    @Bean @Lazy StripePaymentProviderAdapter stripePaymentProvider(StripeCredentialResolver credentials, StripeClientFactory clients,
            PaymentAccountVerificationPort verification, ProviderOperationStore operations, DigestPort digest,
            LoadSitePort sites, LoadPaymentPort payments, LoadPaymentAccountPort accounts,dev.genericpayments.platform.payments.application.port.out.LoadRefundPort refunds) {
        return new StripePaymentProviderAdapter(credentials, clients, verification, operations, digest, sites, payments, accounts,refunds);
    }

    @Bean @Lazy dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeSubscriptionProviderAdapter stripeSubscriptionProvider(
            StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,
            ProviderOperationStore steps,dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort bindings,
            dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingCustomerAdapter customers,DigestPort digest,
            LoadPaymentAccountPort accounts,LoadSitePort sites,dev.genericpayments.platform.customers.application.port.out.CustomerStore customerStore,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionStore subscriptions,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore operations,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCatalogPort catalog,
            dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort mappings,java.time.Clock clock) {
        return new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeSubscriptionProviderAdapter(credentials,clients,verification,steps,bindings,customers,
            digest,accounts,sites,customerStore,subscriptions,operations,catalog,mappings,clock);
    }
}
