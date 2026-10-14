package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface CancelSubscriptionUseCase {
    enum Mode { PERIOD_END, IMMEDIATE }
    record Command(SubscriptionCommandContext context,UUID subscriptionId,Mode mode,UUID confirmation) {}
    SubscriptionOperationResult cancel(Command command);
}
