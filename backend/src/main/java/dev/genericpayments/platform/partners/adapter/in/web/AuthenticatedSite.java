package dev.genericpayments.platform.partners.adapter.in.web;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
/** Security principal contains only verified identity and scopes. */
public record AuthenticatedSite(SiteId siteId, SiteApiKeyId keyId, Set<PartnerScope> scopes) {
    public AuthenticatedSite { scopes=Set.copyOf(scopes); }
}
