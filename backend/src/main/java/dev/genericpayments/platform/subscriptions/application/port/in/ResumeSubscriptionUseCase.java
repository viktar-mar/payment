package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface ResumeSubscriptionUseCase {
    record Command(SubscriptionCommandContext context,UUID subscriptionId,boolean recurringConsent) {}
    SubscriptionOperationResult resume(Command command);
}
