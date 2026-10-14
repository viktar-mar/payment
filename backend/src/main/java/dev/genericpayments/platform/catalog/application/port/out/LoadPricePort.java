package dev.genericpayments.platform.catalog.application.port.out;

import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.PriceKey;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;
import java.util.Optional;

public interface LoadPricePort {
    Optional<Price> findById(PriceId id);
    Optional<Price> findCurrentlyPublished(SiteId siteId, PriceKey key);
    List<Price> findBySiteId(SiteId siteId);
    int nextVersion(SiteId siteId, PriceKey key);
}
