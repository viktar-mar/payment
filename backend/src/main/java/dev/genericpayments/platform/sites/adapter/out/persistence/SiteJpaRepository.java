package dev.genericpayments.platform.sites.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SiteJpaRepository extends JpaRepository<SiteJpaEntity, UUID> {
    Optional<SiteJpaEntity> findByKey(String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select site from SiteJpaEntity site where site.key=:key")
    Optional<SiteJpaEntity> findByKeyForUpdate(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select site from SiteJpaEntity site where site.id=:id")
    Optional<SiteJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByKey(String key);

    List<SiteJpaEntity> findAllByOrderByDisplayNameAsc();
}
