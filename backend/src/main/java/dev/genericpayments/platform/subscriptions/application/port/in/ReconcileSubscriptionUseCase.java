package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface ReconcileSubscriptionUseCase {
    record Command(SubscriptionCommandContext context,UUID subscriptionId) {}
    SubscriptionOperationResult reconcile(Command command);
}
