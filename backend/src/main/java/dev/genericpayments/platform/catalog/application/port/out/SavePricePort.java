package dev.genericpayments.platform.catalog.application.port.out;

import dev.genericpayments.platform.catalog.domain.Price;

public interface SavePricePort {
    Price save(Price price);
}
