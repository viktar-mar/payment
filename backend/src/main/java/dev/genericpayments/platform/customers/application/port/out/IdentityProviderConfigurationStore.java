package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.IdentityProviderConfiguration;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

public interface IdentityProviderConfigurationStore {
    record StoredConfiguration(java.util.UUID id,IdentityProviderConfiguration configuration) {
        public StoredConfiguration { Objects.requireNonNull(id,"provider id"); Objects.requireNonNull(configuration,"provider configuration"); }
    }
    Optional<IdentityProviderConfiguration> find(SiteId siteId, String providerKey);
    /** Requires an existing transaction. */
    Optional<IdentityProviderConfiguration> findForUpdate(SiteId siteId, String providerKey);
    List<IdentityProviderConfiguration> list(SiteId siteId);
    IdentityProviderConfiguration save(IdentityProviderConfiguration configuration);
    /** Explicit key-material write. Caller must validate/canonicalize public signing keys before saving. */
    IdentityProviderConfiguration save(IdentityProviderConfiguration configuration, PublicJwkSet publicJwkSet);
    /** Internal key resolution only; never include the payload in safe/admin views. */
    Optional<PublicJwkSet> findPublicJwkSet(SiteId siteId, String providerKey);
    Optional<StoredConfiguration> findById(SiteId siteId,java.util.UUID providerId);
    List<StoredConfiguration> listStored(SiteId siteId);
    StoredConfiguration saveStored(IdentityProviderConfiguration configuration);
    StoredConfiguration saveStored(IdentityProviderConfiguration configuration,PublicJwkSet publicJwkSet);

    record PublicJwkSet(String canonicalJson) {
        public PublicJwkSet { Objects.requireNonNull(canonicalJson,"public JWK set"); }
        @Override public String toString() { return "PublicJwkSet[redacted]"; }
    }
}
