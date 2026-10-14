package dev.genericpayments.platform.catalog.adapter.in.configuration;

import dev.genericpayments.platform.catalog.application.port.out.CatalogProviderPort;
import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.application.port.out.LoadProductPort;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.catalog.application.port.out.SavePricePort;
import dev.genericpayments.platform.catalog.application.service.CreatePriceService;
import dev.genericpayments.platform.catalog.application.service.PublishPriceService;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
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
    PublishPriceService publishPriceService(LoadPricePort loadPrices, SavePricePort savePrices,
                                            LoadProductPort loadProducts, LoadSitePort loadSites,
                                            LoadPaymentAccountPort loadPaymentAccounts, CatalogProviderPort provider,
                                            ProviderCatalogMappingPort mappings, Clock clock) {
        return new PublishPriceService(loadPrices, savePrices, loadProducts, loadSites, loadPaymentAccounts,
            provider, mappings, clock);
    }
}
