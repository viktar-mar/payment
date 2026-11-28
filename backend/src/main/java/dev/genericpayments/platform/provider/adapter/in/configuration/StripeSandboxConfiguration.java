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
            dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort mappings,java.time.Clock clock,
            org.springframework.beans.factory.ObjectProvider<dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingActionAdapter> actions,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore proofs,
            @org.springframework.beans.factory.annotation.Qualifier("providerReferenceClock") java.time.Clock providerClock) {
        return new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeSubscriptionProviderAdapter(credentials,clients,verification,steps,bindings,customers,
            digest,accounts,sites,customerStore,subscriptions,operations,catalog,mappings,clock,actions::getObject,proofs,providerClock);
    }

    @Bean dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens stripeBillingGrantTokens(Environment environment,StripeSandboxProperties settings){
        return dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens.from(environment,settings.enabled());
    }
    @Bean @Lazy dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership stripeBillingOwnership(
            StripeCredentialResolver credentials,ProviderOperationStore steps,dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort bindings,
            DigestPort digest,LoadPaymentAccountPort accounts,LoadSitePort sites,dev.genericpayments.platform.customers.application.port.out.CustomerStore customers,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionStore subscriptions,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore operations,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCatalogPort catalog,dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort mappings,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore proofs){
        return new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership(credentials,steps,bindings,digest,accounts,sites,customers,subscriptions,operations,catalog,mappings,proofs);
    }
    @Bean @Lazy dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingPortalAdapter stripeBillingPortalAdapter(
            dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership ownership,
            dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort proofs,ProviderOperationStore steps,
            StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,
            @org.springframework.beans.factory.annotation.Qualifier("providerReferenceClock") java.time.Clock clock,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort objects){
        return new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingPortalAdapter(ownership,proofs,steps,credentials,clients,verification,clock,objects);
    }
    @Bean @Lazy dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingConfirmationAdapter stripeBillingConfirmationAdapter(
            dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership ownership,dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens tokens,
            StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,
            @org.springframework.beans.factory.annotation.Qualifier("providerReferenceClock") java.time.Clock clock){
        return new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingConfirmationAdapter(ownership,tokens,credentials,clients,verification,clock);
    }
    @Bean @Lazy dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingActionAdapter stripeBillingActionAdapter(
            dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership ownership,dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens tokens,
            StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore actions,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore operations,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort tx,@org.springframework.beans.factory.annotation.Qualifier("providerReferenceClock") java.time.Clock clock){
        return new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingActionAdapter(ownership,tokens,credentials,clients,verification,actions,operations,tx,clock);
    }

    @Bean @Lazy dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxResolutionPort stripeBillingInboxAdapter(
            LoadPaymentAccountPort accounts,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort objects,
            dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership ownership,
            dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort bindings,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore operations,
            StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,
            dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeSubscriptionProviderAdapter provider,
            dev.genericpayments.platform.subscriptions.application.service.SubscriptionCommandTransaction commands,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore proofs,ProviderOperationStore steps){
        return new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingInboxAdapter(accounts,objects,ownership,bindings,
            operations,credentials,clients,verification,provider,commands,proofs,steps);
    }
}
