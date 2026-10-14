package dev.genericpayments.platform.webhooks.application.port.out;
import dev.genericpayments.platform.webhooks.domain.CallbackConfiguration;
import java.time.Instant;
import java.util.*;
public interface CallbackConfigurationPort {
    record Reveal(CallbackConfiguration configuration,String secret) {
        @Override public String toString(){return "Reveal[configuration="+configuration+", secret=redacted]";}
    }
    /** Durable mutation only: endpoint must already be normalized by CallbackTargetPolicyPort outside a transaction. */
    Reveal configure(UUID site,String endpoint,boolean enabled);
    Reveal rotate(UUID site);
    Optional<CallbackConfiguration> find(UUID site);
    int cleanup(Instant now);
}
