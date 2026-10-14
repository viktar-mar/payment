package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface ClearSubscriptionChangeUseCase {
    record Command(SubscriptionCommandContext context,UUID subscriptionId) {}
    SubscriptionOperationResult clear(Command command);
}
