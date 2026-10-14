package dev.genericpayments.platform.catalog.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import dev.genericpayments.platform.catalog.domain.PriceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

interface PriceJpaRepository extends JpaRepository<PriceJpaEntity, UUID> {
    List<PriceJpaEntity> findAllBySiteId(UUID siteId);
    Optional<PriceJpaEntity> findFirstBySiteIdAndKeyAndStatusOrderByPriceVersionDesc(UUID siteId, String key, PriceStatus status);
}
