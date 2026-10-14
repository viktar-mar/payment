package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.IdentityProviderConfiguration;
import java.time.Instant;

/** Passive, bounded cache observation. Implementations must never resolve or fetch keys. */
public interface JwksDiagnosticsPort {
    enum RefreshState { NOT_APPLICABLE, NEVER_ATTEMPTED, REFRESHING, CURRENT, STALE, REFRESH_FAILED, EXPIRED }
    record Diagnostics(Instant lastSuccessfulRefreshAt,RefreshState state,boolean compatibleKeys) {}
    Diagnostics observe(IdentityProviderConfiguration configuration);
}
