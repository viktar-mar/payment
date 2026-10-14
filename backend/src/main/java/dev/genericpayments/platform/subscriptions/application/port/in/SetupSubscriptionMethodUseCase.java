package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface SetupSubscriptionMethodUseCase {
    record Command(SubscriptionCommandContext context,UUID subscriptionId,boolean recurringConsent) {}
    SubscriptionOperationResult setup(Command command);
}
