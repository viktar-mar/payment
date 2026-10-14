package dev.genericpayments.platform.webhooks.application.port.in;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
public interface ReceiveSubscriptionEventUseCase { boolean receive(SubscriptionProviderEnvelope envelope); }
