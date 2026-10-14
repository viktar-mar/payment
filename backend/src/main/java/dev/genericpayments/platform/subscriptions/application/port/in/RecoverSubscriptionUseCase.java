package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface RecoverSubscriptionUseCase {
    record Command(SubscriptionCommandContext context,UUID subscriptionId,UUID billingRecordId) {}
    SubscriptionOperationResult recover(Command command);
}
