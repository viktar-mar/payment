package dev.genericpayments.platform.shared.adapter.in.web;

import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.application.service.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.service.CreatePaymentAccountService;
import dev.genericpayments.platform.sites.application.port.out.*;
import dev.genericpayments.platform.sites.application.service.SiteService;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
class AdminCatalogConfiguration {
    @Bean CreatePaymentAccountService createPaymentAccountService(LoadPaymentAccountPort load, SavePaymentAccountPort save) {
        return new CreatePaymentAccountService(load, save);
    }
    @Bean SiteService siteService(LoadSitePort load, SaveSitePort save, LoadPaymentAccountPort accounts) {
        return new SiteService(load, save, accounts);
    }
    @Bean CreateProductService createProductService(LoadSitePort sites, SaveProductPort products, Clock clock) {
        return new CreateProductService(sites, products, clock);
    }
    @Bean CatalogQueryService catalogQueryService(LoadProductPort products, LoadPricePort prices) {
        return new CatalogQueryService(products, prices);
    }
}
