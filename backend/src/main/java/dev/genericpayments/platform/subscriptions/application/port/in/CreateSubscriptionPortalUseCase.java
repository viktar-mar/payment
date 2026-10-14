package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface CreateSubscriptionPortalUseCase {
    record Command(SubscriptionCommandContext context,UUID subscriptionId,String returnUrl) {}
    SubscriptionProviderModels.PortalNavigation create(Command command);
}
