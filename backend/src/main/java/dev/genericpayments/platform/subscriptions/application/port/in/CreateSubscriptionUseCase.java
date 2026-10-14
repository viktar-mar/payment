package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.catalog.domain.PriceId;
public interface CreateSubscriptionUseCase {
    SubscriptionOperationResult create(Command command);
    record Command(SubscriptionCommandContext context,PriceId priceId,boolean recurringConsent) {}
}
