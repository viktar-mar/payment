package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.catalog.domain.PriceId;
import java.util.UUID;
public interface PreviewSubscriptionChangeUseCase {
    record Query(SubscriptionCommandContext context,UUID subscriptionId,PriceId targetPriceId,ChangeTiming timing) {}
    SubscriptionQuote preview(Query query);
}
