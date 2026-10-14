package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public record IdentityProviderConfiguration(SiteId siteId, String providerKey, IdentityProviderStatus status,
                                            Configuration configuration, long version) {
    public IdentityProviderConfiguration {
        siteId = CustomerDomainRules.required(siteId, "site id");
        providerKey = CustomerDomainRules.safeKey(providerKey == null ? null : providerKey.toLowerCase(Locale.ROOT),
            "provider key");
        status = CustomerDomainRules.required(status, "provider status");
        configuration = CustomerDomainRules.required(configuration, "provider configuration");
        if (version < 0) throw new DomainValidationException("provider version must not be negative");
    }

    public static IdentityProviderConfiguration create(SiteId siteId, String providerKey, Configuration configuration) {
        return new IdentityProviderConfiguration(siteId, providerKey, IdentityProviderStatus.ACTIVE, configuration, 0);
    }

    public CustomerAuthenticationMode mode() { return configuration.mode(); }

    public IdentityProviderConfiguration disable() {
        if (status == IdentityProviderStatus.DISABLED) return this;
        return new IdentityProviderConfiguration(siteId, providerKey, IdentityProviderStatus.DISABLED, configuration, version);
    }

    @Override
    public String toString() {
        return "IdentityProviderConfiguration[siteId=" + siteId + ", status=" + status + ", mode=" + mode() + "]";
    }

    public sealed interface Configuration permits OidcTokenConfiguration, SiteTokenConfiguration, EmailOtpConfiguration {
        CustomerAuthenticationMode mode();
    }

    public record OidcTokenConfiguration(String issuer, Set<String> audiences, JwksSource jwksSource,
                                         Set<String> allowedAlgorithms, VerifiedEmailClaimMapping verifiedEmailClaims)
            implements Configuration {
        public OidcTokenConfiguration {
            issuer = CustomerDomainRules.reference(issuer, "issuer");
            audiences = immutableAudiences(audiences);
            jwksSource = CustomerDomainRules.required(jwksSource, "JWKS source");
            allowedAlgorithms = immutableAlgorithms(allowedAlgorithms);
        }

        @Override public CustomerAuthenticationMode mode() { return CustomerAuthenticationMode.OIDC_TOKEN; }
        @Override public String toString() { return "OidcTokenConfiguration[redacted]"; }
    }

    public record SiteTokenConfiguration(String issuer, Set<String> audiences, JwksSource jwksSource,
                                         Set<String> allowedAlgorithms) implements Configuration {
        public SiteTokenConfiguration {
            issuer = CustomerDomainRules.reference(issuer, "issuer");
            audiences = immutableAudiences(audiences);
            jwksSource = CustomerDomainRules.required(jwksSource, "JWKS source");
            allowedAlgorithms = immutableAlgorithms(allowedAlgorithms);
        }

        @Override public CustomerAuthenticationMode mode() { return CustomerAuthenticationMode.SITE_TOKEN; }
        @Override public String toString() { return "SiteTokenConfiguration[redacted]"; }
    }

    /** Sender reference resolves outside the domain; it carries neither an email address nor SMTP credentials. */
    public record EmailOtpConfiguration(String senderRef, String templateKey) implements Configuration {
        public EmailOtpConfiguration {
            senderRef = CustomerDomainRules.safeKey(senderRef, "sender configuration");
            templateKey = CustomerDomainRules.safeKey(templateKey, "email template key");
        }

        @Override public CustomerAuthenticationMode mode() { return CustomerAuthenticationMode.EMAIL_OTP; }
        @Override public String toString() { return "EmailOtpConfiguration[redacted]"; }
    }

    public record VerifiedEmailClaimMapping(String emailClaim, String verifiedClaim) {
        public VerifiedEmailClaimMapping {
            emailClaim = CustomerDomainRules.reference(emailClaim, "email claim");
            verifiedClaim = CustomerDomainRules.reference(verifiedClaim, "verified email claim");
        }

        @Override public String toString() { return "VerifiedEmailClaimMapping[redacted]"; }
    }

    private static Set<String> immutableAudiences(Set<String> values) {
        if (values == null || values.isEmpty()) throw new DomainValidationException("at least one audience is required");
        Set<String> audiences = new HashSet<>();
        for (String value : values) {
            String normalized = CustomerDomainRules.reference(value, "audience").trim();
            audiences.add(CustomerDomainRules.reference(normalized, "audience"));
        }
        return Set.copyOf(audiences);
    }

    private static Set<String> immutableAlgorithms(Set<String> values) {
        if (values == null || values.isEmpty()
            || values.stream().anyMatch(value -> !"ES256".equals(value) && !"RS256".equals(value))) {
            throw new DomainValidationException("allowed algorithms must be a non-empty subset of ES256 and RS256");
        }
        return Set.copyOf(values);
    }
}
