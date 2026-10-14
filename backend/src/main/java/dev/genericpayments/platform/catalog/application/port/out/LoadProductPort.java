package dev.genericpayments.platform.catalog.application.port.out;

import dev.genericpayments.platform.catalog.domain.Product;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;
import java.util.Optional;

public interface LoadProductPort {
    Optional<Product> findById(ProductId id);
    List<Product> findBySiteId(SiteId siteId);
}
