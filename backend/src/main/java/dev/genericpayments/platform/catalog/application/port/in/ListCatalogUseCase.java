package dev.genericpayments.platform.catalog.application.port.in;

import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.Product;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;

public interface ListCatalogUseCase {
    List<Product> products(SiteId siteId);
    List<Price> prices(SiteId siteId);
}
