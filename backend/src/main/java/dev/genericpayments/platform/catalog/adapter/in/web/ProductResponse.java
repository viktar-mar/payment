package dev.genericpayments.platform.catalog.adapter.in.web;
import dev.genericpayments.platform.catalog.domain.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
public record ProductResponse(UUID id, @JsonProperty("site_id") UUID siteId, String key, String name, String description, ProductStatus status) {
    static ProductResponse from(Product product) {
        return new ProductResponse(product.id().value(), product.siteId().value(), product.key().value(),
            product.name(), product.description(), product.status());
    }
}
