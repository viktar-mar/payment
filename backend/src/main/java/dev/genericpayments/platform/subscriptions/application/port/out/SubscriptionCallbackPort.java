package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
public interface SubscriptionCallbackPort {
    /** Atomic with the lifecycle journal. Never evaluates expiry at delivery time. */
    void snapshot(Subscription subscription,SubscriptionEvent event,AccessDecision access,Instant now);
}
