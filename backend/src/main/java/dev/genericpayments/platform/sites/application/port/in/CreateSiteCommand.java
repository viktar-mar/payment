package dev.genericpayments.platform.sites.application.port.in;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.AuthenticationMode;
import dev.genericpayments.platform.sites.domain.PaymentMethodPolicy;
import java.net.URI;
import java.util.Set;

public record CreateSiteCommand(String key, String displayName, PaymentAccountId paymentAccountId,
                                Set<URI> allowedOrigins, Set<AuthenticationMode> authenticationModes,
                                PaymentMethodPolicy paymentMethodPolicy) {
    public CreateSiteCommand {
        allowedOrigins = allowedOrigins == null ? null : Set.copyOf(allowedOrigins);
        authenticationModes = authenticationModes == null ? null : Set.copyOf(authenticationModes);
    }
}
