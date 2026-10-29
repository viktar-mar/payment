package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
public interface SubscriptionCallbackPort {
    /**
     * Atomic with the lifecycle journal. The complete access decision and display state are
     * frozen at {@code access.evaluatedAt()}, which must equal {@code event.occurredAt()}.
     * {@code enqueuedAt} supplies operational queue timestamps independently of billing time.
     * Never evaluates expiry at delivery time; equal billing/enqueue instants remain supported.
     */
    void snapshot(Subscription subscription,SubscriptionEvent event,AccessDecision access,Instant enqueuedAt);
}
