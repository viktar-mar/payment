package dev.genericpayments.platform.catalog.application.port.out;

import dev.genericpayments.platform.catalog.domain.Product;

public interface SaveProductPort {
    Product save(Product product);
}
