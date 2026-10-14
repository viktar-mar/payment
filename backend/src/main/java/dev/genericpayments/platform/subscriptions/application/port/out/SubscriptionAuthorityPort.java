package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
public interface SubscriptionAuthorityPort {
    /** Mandatory transaction; locks and revalidates current authority without upgrading captured scopes. */
    void revalidate(SubscriptionCommandContext context,boolean manage);
    void revalidateSite(dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor actor, dev.genericpayments.platform.sites.domain.SiteId site, boolean manage);
}
