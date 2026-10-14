package dev.genericpayments.platform.catalog.application.port.in;

import dev.genericpayments.platform.catalog.domain.Product;

public interface CreateProductUseCase {
    Product create(CreateProductCommand command);
}
