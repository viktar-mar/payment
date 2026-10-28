package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Objects;

/** The reference is a fixed binding, never a path, URL, or arbitrary environment variable. */
public final class StripeCredentialResolver {
    private final StripeSandboxProperties properties;

    public StripeCredentialResolver(StripeSandboxProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public StripeCredentials resolve(PaymentAccount account) {
        Objects.requireNonNull(account);
        if (!properties.enabled() || account.provider() != PaymentProviderType.STRIPE
                || account.mode() != PaymentAccountMode.SANDBOX
                || !"stripe-sandbox-default".equals(account.credentialsRef())) {
            throw new DomainValidationException("Stripe Sandbox credential binding is unavailable");
        }
        return properties.credentials();
    }
}
