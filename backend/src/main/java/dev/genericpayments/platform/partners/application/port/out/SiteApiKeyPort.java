package dev.genericpayments.platform.partners.application.port.out;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Optional;
public interface SiteApiKeyPort {
    boolean insert(SiteApiKey key);
    Optional<SiteApiKey> findByPrefix(String prefix);
    Optional<SiteApiKey> lock(SiteId siteId, SiteApiKeyId keyId);
    void update(SiteApiKey key);
    void touch(SiteApiKeyId keyId, Instant now);
}
