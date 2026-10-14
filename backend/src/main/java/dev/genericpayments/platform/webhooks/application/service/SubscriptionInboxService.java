package dev.genericpayments.platform.webhooks.application.service;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort;
import dev.genericpayments.platform.webhooks.application.port.in.ReceiveSubscriptionEventUseCase;
import org.springframework.stereotype.Service;
@Service
public class SubscriptionInboxService implements ReceiveSubscriptionEventUseCase {
    private final SubscriptionInboxPort inbox;
    public SubscriptionInboxService(SubscriptionInboxPort inbox){this.inbox=inbox;}
    public boolean receive(SubscriptionProviderEnvelope envelope){return inbox.insert(envelope);}
}
