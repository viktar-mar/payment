package dev.genericpayments.platform.payments.adapter.in.configuration;
import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.service.*;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.servlet.handler.MappedInterceptor;
import dev.genericpayments.platform.payments.adapter.in.web.*;
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PublicRateLimitProperties.class)
class CheckoutConfiguration {
    @Bean @Primary RateLimitPort publicRateLimitPort(Clock clock, PublicRateLimitProperties properties) {
        return new InMemoryTokenBucketRateLimitAdapter(clock, properties.createPerMinute(), properties.confirmPerMinute(), properties.readPerMinute(), properties.maxKeys());
    }
    @Bean DynamicSiteCorsConfigurationSource dynamicSiteCorsConfigurationSource(CheckoutAuthorizationService authorization, LoadSitePort sites) {
        return new DynamicSiteCorsConfigurationSource(authorization, sites);
    }
    @Bean MappedInterceptor catalogCheckoutCustomerAuthorizationInterceptor(
        dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver sessions,LoadSitePort sites) {
        return new MappedInterceptor(null,new CatalogCheckoutCustomerAuthorizationInterceptor(sessions,sites));
    }
    @Bean CheckoutAuthorizationService checkoutAuthorizationService(CheckoutSessionPort sessions, LoadSitePort sites, LoadPaymentPort payments, DigestPort digest, Clock clock) {
        return new CheckoutAuthorizationService(sessions, sites, payments, digest, clock);
    }
    @Bean CatalogCheckoutTransaction catalogCheckoutTransaction(IdempotencyService idempotency, LoadPricePort prices, CheckoutSessionPort sessions,
        PaymentCreationTransaction creation, PaymentAttemptTransaction attempts, RandomTokenPort tokens, DigestPort digest, Clock clock) {
        return new CatalogCheckoutTransaction(idempotency, prices, sessions, creation, attempts, tokens, digest, clock);
    }
    @Bean CatalogCheckoutService catalogCheckoutService(CheckoutAuthorizationService authorization, CatalogCheckoutTransaction phases,
        LoadPaymentPort payments, LoadPaymentAttemptPort attempts, LoadPaymentAccountPort accounts, PaymentProviderRegistry providers,
        PaymentPreparationCoordinator preparation, PaymentConfirmationCoordinator confirmation, DigestPort digest, Stage2TelemetryPort telemetry, Clock clock) {
        return new CatalogCheckoutService(authorization, phases, payments, attempts, accounts, providers, preparation, confirmation, digest, telemetry, clock);
    }
}
