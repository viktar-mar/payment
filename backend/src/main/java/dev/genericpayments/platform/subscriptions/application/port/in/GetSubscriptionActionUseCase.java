package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface GetSubscriptionActionUseCase {
    record Query(SubscriptionCommandContext context,UUID subscriptionId,UUID operationId) {}
    /** Authorized ephemeral capability only; HTTP adapters must send Cache-Control: no-store. */
    SubscriptionProviderModels.NextAction get(Query query);
}
