package dev.genericpayments.platform.catalog.adapter.out.persistence;

import dev.genericpayments.platform.catalog.application.port.out.LoadProductPort;
import dev.genericpayments.platform.catalog.application.port.out.SaveProductPort;
import dev.genericpayments.platform.catalog.domain.Product;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class ProductPersistenceAdapter implements LoadProductPort, SaveProductPort {
    private final ProductJpaRepository repository;

    ProductPersistenceAdapter(ProductJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Product save(Product product) {
        ProductJpaEntity entity = repository.findById(product.id().value())
            .map(existing -> {
                existing.update(product);
                return existing;
            })
            .orElseGet(() -> ProductJpaEntity.from(product));
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return repository.findById(id.value()).map(ProductJpaEntity::toDomain);
    }

    @Override
    public List<Product> findBySiteId(SiteId siteId) {
        return repository.findAllBySiteId(siteId.value()).stream().map(ProductJpaEntity::toDomain).toList();
    }
}
