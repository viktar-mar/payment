package dev.genericpayments.platform.catalog.adapter.out.persistence;

import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.application.port.out.SavePricePort;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.PriceKey;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class PricePersistenceAdapter implements LoadPricePort, SavePricePort {
    private static final String NEXT_VERSION = """
        insert into price_version_counters(site_id, price_key, last_version)
        values (?, ?, 1)
        on conflict (site_id, price_key)
        do update set last_version = price_version_counters.last_version + 1
        returning last_version
        """;

    private final PriceJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    PricePersistenceAdapter(PriceJpaRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Price save(Price price) {
        PriceJpaEntity entity = repository.findById(price.id().value())
            .map(existing -> {
                existing.update(price);
                return existing;
            })
            .orElseGet(() -> PriceJpaEntity.from(price));
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<Price> findById(PriceId id) {
        return repository.findById(id.value()).map(PriceJpaEntity::toDomain);
    }

    @Override
    public Optional<Price> findCurrentlyPublished(SiteId siteId, PriceKey key) {
        return repository.findFirstBySiteIdAndKeyAndStatusOrderByPriceVersionDesc(siteId.value(), key.value(),
            dev.genericpayments.platform.catalog.domain.PriceStatus.PUBLISHED).map(PriceJpaEntity::toDomain);
    }

    @Override
    public List<Price> findBySiteId(SiteId siteId) {
        return repository.findAllBySiteId(siteId.value()).stream().map(PriceJpaEntity::toDomain).toList();
    }

    @Override
    public int nextVersion(SiteId siteId, PriceKey key) {
        Integer version = jdbcTemplate.queryForObject(NEXT_VERSION, Integer.class, siteId.value(), key.value());
        if (version == null) {
            throw new IllegalStateException("price version allocation returned no version");
        }
        return version;
    }
}
