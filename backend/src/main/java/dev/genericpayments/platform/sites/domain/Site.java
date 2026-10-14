package dev.genericpayments.platform.sites.domain;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.net.URI;
import java.util.Objects;
import java.util.Set;

public record Site(SiteId id, SiteKey key, String displayName, PaymentAccountId paymentAccountId,
                   SiteStatus status, Set<URI> allowedOrigins,
                   Set<AuthenticationMode> authenticationModes,
                   PaymentMethodPolicy paymentMethodPolicy) {
    public Site {
        id = Objects.requireNonNull(id, "id");
        key = Objects.requireNonNull(key, "key");
        displayName = requireDisplayName(displayName);
        paymentAccountId = Objects.requireNonNull(paymentAccountId, "paymentAccountId");
        status = Objects.requireNonNull(status, "status");
        allowedOrigins = immutableOrigins(allowedOrigins);
        authenticationModes = immutableAuthenticationModes(authenticationModes);
        paymentMethodPolicy = Objects.requireNonNull(paymentMethodPolicy, "paymentMethodPolicy");
    }

    public static Site create(SiteId id, SiteKey key, String displayName,
                              PaymentAccountId paymentAccountId, Set<URI> allowedOrigins,
                              Set<AuthenticationMode> authenticationModes,
                              PaymentMethodPolicy paymentMethodPolicy) {
        return new Site(id, key, displayName, paymentAccountId, SiteStatus.ACTIVE, allowedOrigins,
            authenticationModes, paymentMethodPolicy);
    }

    public Site disable() {
        return new Site(id, key, displayName, paymentAccountId, SiteStatus.DISABLED, allowedOrigins,
            authenticationModes, paymentMethodPolicy);
    }

    private static String requireDisplayName(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("site display name must not be blank");
        }
        if (value.length() > 120) {
            throw new DomainValidationException("site display name must be at most 120 characters");
        }
        return value;
    }

    private static Set<URI> immutableOrigins(Set<URI> origins) {
        if (origins == null || origins.isEmpty()) {
            throw new DomainValidationException("at least one allowed origin is required");
        }
        Set<URI> copied = Set.copyOf(origins);
        copied.forEach(Site::validateOrigin);
        return copied;
    }

    private static Set<AuthenticationMode> immutableAuthenticationModes(Set<AuthenticationMode> modes) {
        if (modes == null || modes.isEmpty()) {
            throw new DomainValidationException("at least one authentication mode is required");
        }
        return Set.copyOf(modes);
    }

    private static void validateOrigin(URI origin) {
        if (origin == null || origin.getScheme() == null || origin.getHost() == null
            || (origin.getRawPath() != null && !origin.getRawPath().isEmpty()) || origin.getRawQuery() != null
            || origin.getRawFragment() != null || origin.getRawUserInfo() != null) {
            throw new DomainValidationException("invalid allowed origin");
        }
        String scheme = origin.getScheme();
        if (!"https".equalsIgnoreCase(scheme)
            && !("http".equalsIgnoreCase(scheme) && isLocalHost(origin.getHost()))) {
            throw new DomainValidationException("invalid allowed origin");
        }
    }

    private static boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
            || "[::1]".equalsIgnoreCase(host) || "::1".equalsIgnoreCase(host);
    }
}
