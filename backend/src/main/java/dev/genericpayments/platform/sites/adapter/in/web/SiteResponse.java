package dev.genericpayments.platform.sites.adapter.in.web;

import dev.genericpayments.platform.sites.domain.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

public record SiteResponse(UUID id, String key, @JsonProperty("display_name") String displayName, SiteStatus status,
                           @JsonProperty("payment_account_id") UUID paymentAccountId,
                           @JsonProperty("allowed_origins") Set<URI> allowedOrigins,
                           @JsonProperty("authentication_modes") Set<AuthenticationMode> authenticationModes,
                           @JsonProperty("payment_method_policy") CreateSiteRequest.Policy paymentMethodPolicy) {
    static SiteResponse from(Site site) {
        return new SiteResponse(site.id().value(), site.key().value(), site.displayName(), site.status(),
            site.paymentAccountId().value(), site.allowedOrigins(), site.authenticationModes(),
            new CreateSiteRequest.Policy(site.paymentMethodPolicy().mode(), site.paymentMethodPolicy().allowedMethods()));
    }
}
