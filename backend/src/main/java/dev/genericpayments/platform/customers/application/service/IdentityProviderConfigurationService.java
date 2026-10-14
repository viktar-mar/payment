package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.in.ManageIdentityProviderConfigurationUseCase;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.net.URI;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class IdentityProviderConfigurationService implements ManageIdentityProviderConfigurationUseCase {
    private final LoadSitePort sites;
    private final IdentityProviderConfigurationStore store;
    private final PublicJwkSetValidationPort publicKeys;
    private final CustomerMailCapability mail;
    private final CustomerMailTemplatePort templates;
    private final EmailOtpProviderSelector emailSelector;
    private final JwksDiagnosticsPort diagnostics;

    public IdentityProviderConfigurationService(LoadSitePort sites,IdentityProviderConfigurationStore store,PublicJwkSetValidationPort publicKeys,
            CustomerMailCapability mail,CustomerMailTemplatePort templates,EmailOtpProviderSelector emailSelector,JwksDiagnosticsPort diagnostics) {
        this.sites=sites; this.store=store; this.publicKeys=publicKeys;
        this.mail=mail; this.templates=templates; this.emailSelector=emailSelector; this.diagnostics=diagnostics;
    }
    @Override public List<View> list(SiteId siteId) {
        requireSite(siteId,null);
        var providers=store.listStored(siteId);
        var selectedEmail=emailSelector.select(providers.stream().map(IdentityProviderConfigurationStore.StoredConfiguration::configuration).toList());
        return providers.stream().map(provider -> view(provider,selectedEmail)).toList();
    }
    @Override public View get(SiteId siteId,UUID providerId) { requireSite(siteId,null); return view(stored(siteId,providerId)); }
    @Override public View create(SiteId siteId,Create command) {
        requireSite(siteId,command.definition().mode());
        var prepared=prepare(siteId,command.providerKey(),IdentityProviderStatus.ACTIVE,0,command.definition());
        if(store.find(siteId,prepared.configuration().providerKey()).isPresent()) throw new DomainValidationException("provider key already exists");
        return view(save(prepared));
    }
    @Override public View update(SiteId siteId,UUID providerId,Update command) {
        var current=stored(siteId,providerId); requireSite(siteId,command.definition().mode());
        if(!current.configuration().providerKey().equals(command.providerKey()) || current.configuration().version()!=command.expectedVersion())
            throw new OptimisticLockingFailureException("Stale provider version");
        if(current.configuration().mode()!=command.definition().mode()) throw new DomainValidationException("provider mode is immutable");
        var prepared=prepare(siteId,command.providerKey(),current.configuration().status(),command.expectedVersion(),command.definition());
        return view(save(prepared));
    }
    @Override public View disable(SiteId siteId,UUID providerId,long expectedVersion) {
        var current=stored(siteId,providerId); requireSite(siteId,current.configuration().mode());
        if(current.configuration().version()!=expectedVersion) throw new OptimisticLockingFailureException("Stale provider version");
        if(current.configuration().status()!=IdentityProviderStatus.ACTIVE) throw new DomainValidationException("provider is already disabled");
        var disabled=current.configuration().disable();
        IdentityProviderConfigurationStore.StoredConfiguration saved;
        if(source(disabled) instanceof JwksSource.StaticPublicJwkSet) {
            var material=store.findPublicJwkSet(siteId,disabled.providerKey()).orElseThrow(()->new DomainValidationException("missing public keys"));
            saved=store.saveStored(disabled,material);
        } else saved=store.saveStored(disabled);
        return view(saved);
    }

    private Prepared prepare(SiteId siteId,String providerKey,IdentityProviderStatus status,long version,Definition input) {
        if(input.mode()==CustomerAuthenticationMode.EMAIL_OTP) {
            if(input.issuer()!=null || input.audiences()!=null || input.allowedAlgorithms()!=null || input.jwksSourceType()!=null
                || input.jwksUri()!=null || input.publicJwkSet()!=null || input.emailClaim()!=null || input.verifiedEmailClaim()!=null)
                throw new DomainValidationException("token settings are not accepted for email providers");
            return new Prepared(new IdentityProviderConfiguration(siteId,providerKey,status,
                new IdentityProviderConfiguration.EmailOtpConfiguration(input.senderRef(),input.templateKey()),version),null);
        }
        if(input.senderRef()!=null || input.templateKey()!=null) throw new DomainValidationException("mail settings are not accepted for token providers");
        validateIssuer(input.issuer());
        validateAlgorithms(input.allowedAlgorithms());
        PublicJwkSetValidationPort.Validated validated=null; JwksSource source;
        if(input.jwksSourceType()==JwksSourceType.STATIC_PUBLIC) {
            if(input.publicJwkSet()==null || input.jwksUri()!=null) throw new DomainValidationException("static JWKS material is required");
            validated=publicKeys.validate(input.publicJwkSet());
            if(validated.keys().stream().anyMatch(key->!input.allowedAlgorithms().contains(key.algorithm())))
                throw new DomainValidationException("public key algorithm is not allowed");
            source=new JwksSource.StaticPublicJwkSet("static:"+providerKey);
        } else if(input.jwksSourceType()==JwksSourceType.REMOTE) {
            if(input.jwksUri()==null || input.publicJwkSet()!=null) throw new DomainValidationException("remote JWKS URI is required");
            URI uri;
            try { uri=URI.create(input.jwksUri()); } catch(RuntimeException invalid) { throw new DomainValidationException("invalid JWKS URI"); }
            if(uri.getRawQuery()!=null) throw new DomainValidationException("JWKS URI query is not allowed");
            source=new JwksSource.Remote(uri);
        } else throw new DomainValidationException("JWKS source is required");
        IdentityProviderConfiguration.Configuration details=switch(input.mode()) {
            case OIDC_TOKEN -> new IdentityProviderConfiguration.OidcTokenConfiguration(input.issuer(),input.audiences(),source,input.allowedAlgorithms(),claims(input));
            case SITE_TOKEN -> {
                if(input.emailClaim()!=null || input.verifiedEmailClaim()!=null) throw new DomainValidationException("SITE_TOKEN email claims are not accepted");
                yield new IdentityProviderConfiguration.SiteTokenConfiguration(input.issuer(),input.audiences(),source,input.allowedAlgorithms());
            }
            default -> throw new DomainValidationException("unsupported provider mode");
        };
        return new Prepared(new IdentityProviderConfiguration(siteId,providerKey,status,details,version),validated);
    }
    private static IdentityProviderConfiguration.VerifiedEmailClaimMapping claims(Definition input) {
        if(input.emailClaim()==null && input.verifiedEmailClaim()==null) return null;
        if(input.emailClaim()==null || input.verifiedEmailClaim()==null) throw new DomainValidationException("both verified email claims are required");
        return new IdentityProviderConfiguration.VerifiedEmailClaimMapping(input.emailClaim(),input.verifiedEmailClaim());
    }
    private IdentityProviderConfigurationStore.StoredConfiguration save(Prepared prepared) {
        return prepared.keys()==null?store.saveStored(prepared.configuration()):store.saveStored(prepared.configuration(),prepared.keys().material());
    }
    private View view(IdentityProviderConfigurationStore.StoredConfiguration stored) {
        var configuration=stored.configuration();
        var selectedEmail=configuration.mode()==CustomerAuthenticationMode.EMAIL_OTP && configuration.status()==IdentityProviderStatus.ACTIVE
            ?emailSelector.select(store.list(configuration.siteId())):Optional.<IdentityProviderConfiguration>empty();
        return view(stored,selectedEmail);
    }
    private View view(IdentityProviderConfigurationStore.StoredConfiguration stored,Optional<IdentityProviderConfiguration> selectedEmail) {
        var configuration=stored.configuration(); var details=configuration.configuration();
        if(details instanceof IdentityProviderConfiguration.EmailOtpConfiguration email) {
            Readiness readiness;
            if(configuration.status()==IdentityProviderStatus.DISABLED) readiness=Readiness.DISABLED;
            else if(selectedEmail.filter(selected -> selected.providerKey().equals(configuration.providerKey())).isEmpty())
                readiness=Readiness.NOT_SELECTED;
            else readiness=mail.available(email.senderRef())&&templates.available(email.templateKey())?Readiness.READY:Readiness.CONFIGURATION_UNAVAILABLE;
            return new View(stored.id(),configuration.providerKey(),configuration.mode(),configuration.status(),configuration.version(),null,Set.of(),Set.of(),
                null,readiness,List.of(),email.senderRef(),email.templateKey(),null,JwksDiagnosticsPort.RefreshState.NOT_APPLICABLE);
        }
        String issuer; Set<String> audiences,algorithms; JwksSource jwks;
        if(details instanceof IdentityProviderConfiguration.OidcTokenConfiguration oidc) {
            issuer=oidc.issuer(); audiences=oidc.audiences(); algorithms=oidc.allowedAlgorithms(); jwks=oidc.jwksSource();
        } else if(details instanceof IdentityProviderConfiguration.SiteTokenConfiguration site) {
            issuer=site.issuer(); audiences=site.audiences(); algorithms=site.allowedAlgorithms(); jwks=site.jwksSource();
        } else throw new DomainValidationException("unsupported provider mode");
        if(jwks instanceof JwksSource.StaticPublicJwkSet) {
            var metadata=publicKeys.validate(store.findPublicJwkSet(configuration.siteId(),configuration.providerKey())
                .orElseThrow(()->new DomainValidationException("missing public keys")).canonicalJson()).keys();
            return new View(stored.id(),configuration.providerKey(),configuration.mode(),configuration.status(),configuration.version(),issuer,audiences,algorithms,
                JwksSourceType.STATIC_PUBLIC,configuration.status()==IdentityProviderStatus.DISABLED?Readiness.DISABLED:Readiness.READY,metadata,
                null,null,null,JwksDiagnosticsPort.RefreshState.NOT_APPLICABLE);
        }
        var observed=diagnostics.observe(configuration);
        var readiness=configuration.status()==IdentityProviderStatus.DISABLED?Readiness.DISABLED:
            observed.state()==JwksDiagnosticsPort.RefreshState.CURRENT&&observed.compatibleKeys()?Readiness.READY:Readiness.CONFIGURED_UNVERIFIED;
        return new View(stored.id(),configuration.providerKey(),configuration.mode(),configuration.status(),configuration.version(),issuer,audiences,algorithms,
            JwksSourceType.REMOTE,readiness,List.of(),null,null,observed.lastSuccessfulRefreshAt(),observed.state());
    }
    private IdentityProviderConfigurationStore.StoredConfiguration stored(SiteId siteId,UUID providerId) {
        return store.findById(siteId,providerId).orElseThrow(()->new DomainValidationException("provider not found"));
    }
    private void requireSite(SiteId siteId,CustomerAuthenticationMode mode) {
        var site=sites.findById(siteId).orElseThrow(()->new DomainValidationException("site not found"));
        if(site.status()!=SiteStatus.ACTIVE) throw new DomainValidationException("site is disabled");
        if(mode!=null && !site.authenticationModes().contains(AuthenticationMode.valueOf(mode.name())))
            throw new DomainValidationException("authentication mode is not enabled");
    }
    private static void validateIssuer(String issuer) {
        try {
            URI value=URI.create(issuer);
            if(!"https".equalsIgnoreCase(value.getScheme()) || value.getHost()==null || value.getRawUserInfo()!=null
                || value.getRawQuery()!=null || value.getRawFragment()!=null) throw new DomainValidationException("invalid issuer");
        } catch(NullPointerException|IllegalArgumentException invalid) {
            if(invalid instanceof DomainValidationException domain) throw domain;
            throw new DomainValidationException("invalid issuer");
        }
    }
    private static void validateAlgorithms(Set<String> algorithms) {
        if(algorithms==null || algorithms.isEmpty())
            throw new DomainValidationException("allowed algorithms must be a non-empty subset of ES256 and RS256");
    }
    private static JwksSource source(IdentityProviderConfiguration value) {
        return switch(value.configuration()) {
            case IdentityProviderConfiguration.OidcTokenConfiguration oidc -> oidc.jwksSource();
            case IdentityProviderConfiguration.SiteTokenConfiguration site -> site.jwksSource();
            case IdentityProviderConfiguration.EmailOtpConfiguration email -> null;
        };
    }
    private record Prepared(IdentityProviderConfiguration configuration,PublicJwkSetValidationPort.Validated keys) {}
}
