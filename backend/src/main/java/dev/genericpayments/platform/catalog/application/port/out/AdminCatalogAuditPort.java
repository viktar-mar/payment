package dev.genericpayments.platform.catalog.application.port.out;

import dev.genericpayments.platform.catalog.application.port.in.PricePublicationAudit;
import dev.genericpayments.platform.catalog.domain.Price;

public interface AdminCatalogAuditPort {
    void pricePublished(Price price, PricePublicationAudit context);
}
