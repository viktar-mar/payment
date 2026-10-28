package dev.genericpayments.platform.catalog.application.port.in;

import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;

public interface PublishPriceUseCase {
    Price publish(PriceId id);
    Price publish(PriceId id, PricePublicationAudit audit);
}
