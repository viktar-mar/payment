package dev.genericpayments.platform.customers.adapter.out.persistence;

import static dev.genericpayments.platform.customers.adapter.out.persistence.PostgresTimestamps.canonical;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class CustomerPersistenceMapper {
    private final ObjectMapper json;
    CustomerPersistenceMapper(ObjectMapper json) { this.json=json; }

    record Digest(String value,String keyVersion) {
        static Digest parse(String reference) {
            if(reference==null || reference.length()>512 || !reference.matches("v[1-9][0-9]{0,8}:[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException("Invalid versioned digest");
            int colon=reference.indexOf(':');
            return new Digest(reference.substring(colon+1),reference.substring(0,colon));
        }
        String reference() { return keyVersion+":"+value; }
        @Override public String toString() { return "Digest[redacted]"; }
    }
    Customer customer(CustomerJpaEntity e) {
        CustomerId id=new CustomerId(e.id);
        return Customer.reconstitute(id,new SiteId(e.siteId),CustomerStatus.valueOf(e.status),
            e.emailCiphertext==null?null:CustomerStore.emailReference(id),
            e.emailDigest==null?null:new Digest(e.emailDigest,e.emailDigestKeyVersion).reference(),
            canonical(e.createdAt),canonical(e.updatedAt),canonical(e.anonymizedAt),e.version);
    }
    CustomerIdentity identity(CustomerIdentityJpaEntity e) {
        return CustomerIdentity.reconstitute(new CustomerIdentityId(e.id),new SiteId(e.siteId),new CustomerId(e.customerId),
            CustomerAuthenticationMode.valueOf(e.mode),e.issuerKey,new Digest(e.subjectDigest,e.subjectDigestKeyVersion).reference(),
            canonical(e.verifiedAt),canonical(e.createdAt),canonical(e.lastUsedAt));
    }
    IdentityProviderConfiguration provider(IdentityProviderConfigurationJpaEntity e) {
        IdentityProviderConfiguration.Configuration config;
        if("EMAIL_OTP".equals(e.mode)) config=new IdentityProviderConfiguration.EmailOtpConfiguration(e.senderRef,e.templateKey);
        else {
            JwksSource jwks="REMOTE".equals(e.jwksSourceType)?new JwksSource.Remote(URI.create(e.jwksLocation)):new JwksSource.StaticPublicJwkSet(e.publicJwkSetRef);
            if("SITE_TOKEN".equals(e.mode)) config=new IdentityProviderConfiguration.SiteTokenConfiguration(e.issuer,strings(e.audiences),jwks,strings(e.allowedAlgorithms));
            else config=new IdentityProviderConfiguration.OidcTokenConfiguration(e.issuer,strings(e.audiences),jwks,strings(e.allowedAlgorithms),
                e.emailClaim==null?null:new IdentityProviderConfiguration.VerifiedEmailClaimMapping(e.emailClaim,e.verifiedEmailClaim));
        }
        return new IdentityProviderConfiguration(new SiteId(e.siteId),e.providerKey,IdentityProviderStatus.valueOf(e.status),config,e.version);
    }
    void copyProvider(IdentityProviderConfiguration value,IdentityProviderConfigurationJpaEntity e) {
        e.mode=value.mode().name(); e.status=value.status().name();
        e.issuer=null; e.audiences=null; e.allowedAlgorithms=null; e.jwksSourceType=null; e.jwksLocation=null; e.publicJwkSetRef=null;
        e.emailClaim=null; e.verifiedEmailClaim=null; e.senderRef=null; e.templateKey=null;
        switch(value.configuration()) {
            case IdentityProviderConfiguration.EmailOtpConfiguration config -> { e.senderRef=config.senderRef(); e.templateKey=config.templateKey(); }
            case IdentityProviderConfiguration.SiteTokenConfiguration config -> tokenConfig(e,config.issuer(),config.audiences(),config.allowedAlgorithms(),config.jwksSource());
            case IdentityProviderConfiguration.OidcTokenConfiguration config -> {
                tokenConfig(e,config.issuer(),config.audiences(),config.allowedAlgorithms(),config.jwksSource());
                if(config.verifiedEmailClaims()!=null) { e.emailClaim=config.verifiedEmailClaims().emailClaim(); e.verifiedEmailClaim=config.verifiedEmailClaims().verifiedClaim(); }
            }
        }
    }
    private void tokenConfig(IdentityProviderConfigurationJpaEntity e,String issuer,Set<String> audiences,Set<String> algorithms,JwksSource source) {
        e.issuer=issuer; e.audiences=json.writeValueAsString(new TreeSet<>(audiences)); e.allowedAlgorithms=json.writeValueAsString(new TreeSet<>(algorithms));
        switch(source) {
            case JwksSource.Remote remote -> { e.jwksSourceType="REMOTE"; e.jwksLocation=remote.uri().toString(); }
            case JwksSource.StaticPublicJwkSet keys -> { e.jwksSourceType="STATIC_PUBLIC"; e.publicJwkSetRef=keys.publicJwkSetRef(); }
        }
    }
    private Set<String> strings(String stored) {
        var array=json.readTree(stored);
        if(!array.isArray()) throw new IllegalArgumentException("Provider configuration must contain an array");
        Set<String> result=new HashSet<>();
        for(var value:array) {
            if(!value.isString()) throw new IllegalArgumentException("Provider configuration must contain strings");
            result.add(value.asString());
        }
        return Set.copyOf(result);
    }
}
