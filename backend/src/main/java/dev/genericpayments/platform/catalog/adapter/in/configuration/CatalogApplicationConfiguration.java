package dev.genericpayments.platform.catalog.adapter.in.configuration;

import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.application.service.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeCatalogProviderAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CatalogApplicationConfiguration {
    @Bean
    CreatePriceService createPriceService(LoadProductPort loadProducts, LoadPricePort loadPrices,
                                          SavePricePort savePrices, Clock clock) {
        return new CreatePriceService(loadProducts, loadPrices, savePrices, clock);
    }

    @Bean
    PricePublicationTransaction pricePublicationTransaction(LoadPricePort prices, SavePricePort savePrices,
            LoadProductPort products, LoadSitePort sites, LoadPaymentAccountPort accounts,
            ProviderCatalogMappingPort mappings, CatalogPublicationLockPort locks, AdminCatalogAuditPort audit, Clock clock) {
        return new PricePublicationTransaction(prices, savePrices, products, sites, accounts, mappings, locks, audit, clock);
    }

    @Bean
    StripeCatalogProviderAdapter stripeCatalogProvider(StripeCredentialResolver credentials, StripeClientFactory clients,
            ProviderOperationStore operations, ProviderCatalogMappingPort mappings, DigestPort digest,
            PaymentAccountVerificationPort verification) {
        return new StripeCatalogProviderAdapter(credentials, clients, operations, mappings, digest, verification);
    }

    @Bean
    CatalogProviderRegistry catalogProviderRegistry(FakeCatalogProviderAdapter fake, StripeCatalogProviderAdapter stripe,
                                                    StripeSandboxProperties properties) {
        return account -> switch (account.provider()) {
            case FAKE -> fake;
            case STRIPE -> {
                if (!properties.enabled()) throw new PaymentApplicationException("PAYMENT_CAPABILITY_UNAVAILABLE", 422, "Payment capability unavailable");
                yield stripe;
            }
        };
    }

    @Bean
    PublishPriceService publishPriceService(PricePublicationTransaction transaction, CatalogProviderRegistry providers,
            ProviderCatalogMappingPort mappings) {
        return new PublishPriceService(transaction, providers, mappings);
    }
}
