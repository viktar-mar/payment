package dev.genericpayments.platform.catalog.application.service;

import dev.genericpayments.platform.catalog.application.port.in.ListCatalogUseCase;
import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.application.port.out.LoadProductPort;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.Product;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;
import java.util.Objects;

public final class CatalogQueryService implements ListCatalogUseCase {
    private final LoadProductPort loadProducts;
    private final LoadPricePort loadPrices;

    public CatalogQueryService(LoadProductPort loadProducts, LoadPricePort loadPrices) {
        this.loadProducts = Objects.requireNonNull(loadProducts);
        this.loadPrices = Objects.requireNonNull(loadPrices);
    }

    @Override public List<Product> products(SiteId siteId) {
        return List.copyOf(loadProducts.findBySiteId(siteId));
    }

    @Override public List<Price> prices(SiteId siteId) {
        return List.copyOf(loadPrices.findBySiteId(siteId));
    }
}
