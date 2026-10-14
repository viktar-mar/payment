package dev.genericpayments.platform.catalog.adapter.out.persistence;

import dev.genericpayments.platform.catalog.domain.Product;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.catalog.domain.ProductKey;
import dev.genericpayments.platform.catalog.domain.ProductStatus;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
class ProductJpaEntity {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "product_key", nullable = false, length = 63)
    private String key;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProductStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ProductJpaEntity() {
    }

    private ProductJpaEntity(Product product) {
        update(product);
    }

    static ProductJpaEntity from(Product product) {
        return new ProductJpaEntity(product);
    }

    void update(Product product) {
        id = product.id().value();
        siteId = product.siteId().value();
        key = product.key().value();
        name = product.name();
        description = product.description();
        status = product.status();
        createdAt = product.createdAt();
        updatedAt = product.updatedAt();
    }

    Product toDomain() {
        return new Product(new ProductId(id), new SiteId(siteId), ProductKey.of(key), name, description, status,
            createdAt, updatedAt);
    }
}
