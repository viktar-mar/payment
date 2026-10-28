package dev.genericpayments.platform.webhooks.application.port.out;

import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import java.util.Optional;
import java.util.UUID;

/** Worker-side identity preparation, completed before lifecycle reload/read-fence capture. */
public interface SubscriptionInboxResolutionPort {
    Optional<UUID> resolve(SubscriptionProviderEnvelope envelope);
}
