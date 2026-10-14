package dev.genericpayments.platform.catalog.application.port.in;

import dev.genericpayments.platform.catalog.domain.Price;

public interface CreatePriceUseCase {
    Price create(CreatePriceCommand command);
}
