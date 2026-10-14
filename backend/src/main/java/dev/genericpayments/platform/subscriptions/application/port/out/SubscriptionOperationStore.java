package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.UUID;
public interface SubscriptionOperationStore {
    record Claim(SubscriptionOperation operation,boolean owner) {}
    Claim claim(SubscriptionOperation operation);
    SubscriptionOperation lock(UUID id);
    /** Persist candidate version expectedVersion+1; CREATE attachment must precede provider invocation. */
    SubscriptionOperation save(SubscriptionOperation operation,long expectedVersion);
    boolean hasUnresolved(SiteId site,UUID subscription);
    java.util.Optional<SubscriptionOperation> find(SiteId site,UUID subscription,UUID operation);
    java.util.List<SubscriptionOperation> unresolved(SiteId site,UUID subscription);
}
