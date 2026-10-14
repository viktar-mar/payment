package dev.genericpayments.platform.subscriptions.domain;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.Objects;

public record PlanTransition(SiteId siteId, ProductId productId, PriceId fromPriceId, PriceId toPriceId,
                             Direction direction, boolean immediateAllowed, long policyVersion) {
    public enum Direction { UPGRADE, DOWNGRADE, LATERAL }

    public PlanTransition {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(fromPriceId, "fromPriceId");
        Objects.requireNonNull(toPriceId, "toPriceId");
        Objects.requireNonNull(direction, "direction");
        if (fromPriceId.equals(toPriceId) || policyVersion < 0) {
            throw new DomainValidationException("Invalid plan transition");
        }
    }
}
