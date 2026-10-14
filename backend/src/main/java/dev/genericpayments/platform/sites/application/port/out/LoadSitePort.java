package dev.genericpayments.platform.sites.application.port.out;

import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import java.util.List;
import java.util.Optional;

public interface LoadSitePort {
    Optional<Site> findById(SiteId id);

    Optional<Site> findByKey(SiteKey key);

    boolean existsByKey(SiteKey key);

    List<Site> findAll();
}
