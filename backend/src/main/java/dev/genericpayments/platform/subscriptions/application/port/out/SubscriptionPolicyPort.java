package dev.genericpayments.platform.subscriptions.application.port.out;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.PlanTransition;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import java.util.List;
import java.util.Optional;

public interface SubscriptionPolicyPort {
    SubscriptionPolicy load(SiteId siteId);
    SubscriptionPolicy save(SubscriptionPolicy policy, long expectedVersion);
    Optional<PlanTransition> transition(SiteId siteId, ProductId productId, PriceId fromPriceId, PriceId toPriceId);
    List<PlanTransition> transitions(SiteId siteId, ProductId productId);
    void saveTransition(PlanTransition transition);
}
