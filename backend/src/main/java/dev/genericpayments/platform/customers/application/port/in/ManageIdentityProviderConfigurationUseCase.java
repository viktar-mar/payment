package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.port.out.PublicJwkSetValidationPort.KeyMetadata;
import dev.genericpayments.platform.customers.application.port.out.JwksDiagnosticsPort.RefreshState;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
import java.time.Instant;

public interface ManageIdentityProviderConfigurationUseCase {
    enum JwksSourceType { REMOTE,STATIC_PUBLIC }
    enum Readiness { READY,CONFIGURED_UNVERIFIED,DISABLED,CONFIGURATION_UNAVAILABLE,NOT_SELECTED }
    record Definition(CustomerAuthenticationMode mode,String issuer,Set<String> audiences,Set<String> allowedAlgorithms,
            JwksSourceType jwksSourceType,String jwksUri,String publicJwkSet,String emailClaim,String verifiedEmailClaim,
            String senderRef,String templateKey) {
        @Override public String toString() { return "IdentityProviderDefinition[redacted]"; }
    }
    record Create(String providerKey,Definition definition) {
        @Override public String toString() { return "CreateIdentityProvider[redacted]"; }
    }
    record Update(String providerKey,long expectedVersion,Definition definition) {
        @Override public String toString() { return "UpdateIdentityProvider[redacted]"; }
    }
    record View(UUID id,String providerKey,CustomerAuthenticationMode mode,IdentityProviderStatus status,long version,
            String issuer,Set<String> audiences,Set<String> allowedAlgorithms,JwksSourceType jwksSourceType,
            Readiness readiness,List<KeyMetadata> keys,
            String senderRef,String templateKey,Instant lastSuccessfulRefreshAt,RefreshState jwksRefreshState) {
        public View { audiences=Set.copyOf(audiences); allowedAlgorithms=Set.copyOf(allowedAlgorithms); keys=List.copyOf(keys); }
        @Override public String toString() { return "IdentityProviderView[redacted]"; }
    }

    List<View> list(SiteId siteId);
    View get(SiteId siteId,UUID providerId);
    View create(SiteId siteId,Create command);
    View update(SiteId siteId,UUID providerId,Update command);
    View disable(SiteId siteId,UUID providerId,long expectedVersion);
}
