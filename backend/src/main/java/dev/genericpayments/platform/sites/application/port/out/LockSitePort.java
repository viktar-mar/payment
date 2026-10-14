package dev.genericpayments.platform.sites.application.port.out;

import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import java.util.Optional;

public interface LockSitePort {
    /** Requires an existing transaction and holds a write lock on the Site parent row. */
    Optional<Site> findByKeyForUpdate(SiteKey key);
    /** Requires an existing transaction and holds a write lock on the Site parent row. */
    Optional<Site> findByIdForUpdate(SiteId id);
}
