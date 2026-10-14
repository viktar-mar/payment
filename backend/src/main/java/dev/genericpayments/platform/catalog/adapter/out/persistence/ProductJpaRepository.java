package dev.genericpayments.platform.catalog.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, UUID> {
    List<ProductJpaEntity> findAllBySiteId(UUID siteId);
}
