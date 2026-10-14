package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface ChangeSubscriptionPlanUseCase {
    record Command(SubscriptionCommandContext context,UUID subscriptionId,UUID quoteId) {}
    SubscriptionOperationResult change(Command command);
}
