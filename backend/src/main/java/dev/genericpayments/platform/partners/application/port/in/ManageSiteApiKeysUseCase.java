package dev.genericpayments.platform.partners.application.port.in;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Set;
public interface ManageSiteApiKeysUseCase {
    record Reveal(SiteApiKey key, String apiKey) {
        @Override public String toString() { return "Reveal[keyId=" + key.id() + ", secret=redacted]"; }
    }
    Reveal create(SiteId siteId, Set<PartnerScope> scopes, Instant expiresAt);
    Reveal rotate(SiteId siteId, SiteApiKeyId keyId);
    void revoke(SiteId siteId, SiteApiKeyId keyId);
}
