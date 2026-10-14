package dev.genericpayments.platform.partners.application.port.in;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
public record AuthenticatedSite(SiteId siteId, SiteApiKeyId keyId, Set<PartnerScope> scopes) {
    public AuthenticatedSite { Objects.requireNonNull(siteId); Objects.requireNonNull(keyId); scopes = Set.copyOf(scopes); }
}
