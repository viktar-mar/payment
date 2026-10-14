package dev.genericpayments.platform.subscriptions.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.Objects;
import java.util.Set;

public record SubscriptionPolicy(SiteId siteId, long version, boolean enabled, int graceDays,
                                 boolean allowImmediateCancellation, boolean portalEnabled,
                                 boolean deferUpgrades, Set<String> portalReturnUrls) {
    public SubscriptionPolicy {
        Objects.requireNonNull(siteId, "siteId");
        if (version < 0 || graceDays < 0) throw new DomainValidationException("Invalid subscription policy");
        portalReturnUrls = Set.copyOf(portalReturnUrls);
    }

    public static SubscriptionPolicy defaults(SiteId siteId) {
        return new SubscriptionPolicy(siteId, 0, false, 3, false, false, false, Set.of());
    }
}
