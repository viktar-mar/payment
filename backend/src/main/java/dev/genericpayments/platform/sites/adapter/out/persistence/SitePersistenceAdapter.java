package dev.genericpayments.platform.sites.adapter.out.persistence;

import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.application.port.out.SaveSitePort;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class SitePersistenceAdapter implements LoadSitePort, SaveSitePort, LockSitePort {
    private final SiteJpaRepository repository;
    private final Clock clock;

    SitePersistenceAdapter(SiteJpaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Site save(Site site) {
        SiteJpaEntity entity = repository.findByIdForUpdate(site.id().value())
            .map(existing -> {
                existing.update(site, clock.instant());
                return existing;
            })
            .orElseGet(() -> SiteJpaEntity.from(site, clock.instant()));
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<Site> findById(SiteId id) {
        return repository.findById(id.value()).map(SiteJpaEntity::toDomain);
    }

    @Override
    public Optional<Site> findByKey(SiteKey key) {
        return repository.findByKey(key.value()).map(SiteJpaEntity::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Site> findByKeyForUpdate(SiteKey key) {
        return repository.findByKeyForUpdate(key.value()).map(SiteJpaEntity::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Site> findByIdForUpdate(SiteId id) {
        return repository.findByIdForUpdate(id.value()).map(SiteJpaEntity::toDomain);
    }

    @Override
    public boolean existsByKey(SiteKey key) {
        return repository.existsByKey(key.value());
    }

    @Override
    public List<Site> findAll() {
        return repository.findAllByOrderByDisplayNameAsc().stream().map(SiteJpaEntity::toDomain).toList();
    }
}
