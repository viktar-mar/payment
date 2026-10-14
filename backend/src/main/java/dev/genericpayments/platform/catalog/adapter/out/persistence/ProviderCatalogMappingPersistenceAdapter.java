package dev.genericpayments.platform.catalog.adapter.out.persistence;

import dev.genericpayments.platform.catalog.application.model.ProviderCatalogMapping;
import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProviderCatalogMappingPersistenceAdapter implements ProviderCatalogMappingPort {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<ProviderProductRef> findProduct(PaymentAccountId accountId, ProductId productId) {
        return entityManager.createQuery("""
                select mapping from ProviderProductMappingJpaEntity mapping
                where mapping.paymentAccountId = :accountId and mapping.productId = :productId
                """, ProviderProductMappingJpaEntity.class)
            .setParameter("accountId", accountId.value())
            .setParameter("productId", productId.value())
            .getResultList()
            .stream()
            .findFirst()
            .map(ProviderProductMappingJpaEntity::toReference);
    }

    @Override
    public Optional<ProviderPriceRef> findPrice(PaymentAccountId accountId, PriceId priceId) {
        return entityManager.createQuery("""
                select mapping from ProviderPriceMappingJpaEntity mapping
                where mapping.paymentAccountId = :accountId and mapping.priceId = :priceId
                """, ProviderPriceMappingJpaEntity.class)
            .setParameter("accountId", accountId.value())
            .setParameter("priceId", priceId.value())
            .getResultList()
            .stream()
            .findFirst()
            .map(ProviderPriceMappingJpaEntity::toReference);
    }

    @Override
    @Transactional
    public ProviderCatalogMapping save(ProviderCatalogMapping mapping) {
        if (findProduct(mapping.paymentAccountId(), mapping.productId()).isEmpty()) {
            entityManager.persist(new ProviderProductMappingJpaEntity(mapping.paymentAccountId(), mapping.productId(),
                mapping.productRef(), mapping.createdAt()));
        }
        if (findPrice(mapping.paymentAccountId(), mapping.priceId()).isEmpty()) {
            entityManager.persist(new ProviderPriceMappingJpaEntity(mapping.paymentAccountId(), mapping.priceId(),
                mapping.priceRef(), mapping.createdAt()));
        }
        return mapping;
    }
}
