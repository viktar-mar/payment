package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.SiteKey;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class AuthenticationTransactionService {
    private final LockSitePort sites; private final IdentityProviderConfigurationStore providers;
    private final AuthenticationAttemptStore attempts; private final ConsumedSiteTokenStore consumed;
    private final Clock clock;
    AuthenticationTransactionService(LockSitePort sites,IdentityProviderConfigurationStore providers,AuthenticationAttemptStore attempts,ConsumedSiteTokenStore consumed,Clock clock) {
        this.sites=sites; this.providers=providers; this.attempts=attempts; this.consumed=consumed; this.clock=clock;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public OidcAuthenticationAttempt begin(String siteKey,String providerKey,String origin,AuthenticationIntent intent,String digest,Instant now,Instant expiresAt) {
        var context=context(siteKey,providerKey,origin,CustomerAuthenticationMode.OIDC_TOKEN);
        var value=new OidcAuthenticationAttempt(java.util.UUID.randomUUID(),context.site().id(),providerKey,context.origin(),intent,digest,
            context.provider().version(),now,expiresAt,null,0);
        return attempts.save(value);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public OidcAuthenticationAttempt consumeOidc(java.util.UUID id,String siteKey,String providerKey,String origin,AuthenticationIntent intent,
            String nonce,long verifiedVersion,KeyedDigestPort digests) {
        var attempt=attempts.findForUpdate(id).orElseThrow(CustomerAuthenticationSupport::failed);
        var context=context(siteKey,providerKey,origin,CustomerAuthenticationMode.OIDC_TOKEN);
        Instant now=clock.instant();
        if(!attempt.siteId().equals(context.site().id()) || !attempt.providerKey().equals(providerKey) || !attempt.origin().equals(context.origin())
            || attempt.intent()!=intent || attempt.providerConfigVersion()!=verifiedVersion || context.provider().version()!=verifiedVersion
            || !attempt.activeAt(now) || !digests.matches(KeyedDigestPort.Purpose.OIDC_NONCE,nonce,digest(attempt.nonceDigest()))) throw CustomerAuthenticationSupport.failed();
        return attempts.save(attempt.consume(now));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void consumeSiteToken(String siteKey,String providerKey,String origin,long verifiedVersion,List<ConsumedSiteTokenJti> jtis) {
        var context=context(siteKey,providerKey,origin,CustomerAuthenticationMode.SITE_TOKEN);
        if(jtis.isEmpty() || context.provider().version()!=verifiedVersion
            || jtis.stream().anyMatch(jti -> !context.site().id().equals(jti.siteId()) || !providerKey.equals(jti.issuerKey()))) {
            throw CustomerAuthenticationSupport.failed();
        }
        consumed.saveAll(jtis);
    }
    private CustomerAuthenticationPolicy.Context context(String siteKey,String providerKey,String origin,CustomerAuthenticationMode mode) {
        String normalized=CustomerAuthenticationSupport.origin(origin);
        var site=sites.findByKeyForUpdate(SiteKey.of(siteKey)).orElseThrow(CustomerAuthenticationSupport::failed);
        CustomerAuthenticationSupport.active(site,mode,normalized);
        var provider=providers.findForUpdate(site.id(),providerKey).orElseThrow(CustomerAuthenticationSupport::failed);
        if(provider.status()!=IdentityProviderStatus.ACTIVE || provider.mode()!=mode) throw CustomerAuthenticationSupport.failed();
        return new CustomerAuthenticationPolicy.Context(site,provider,normalized);
    }
    private static KeyedDigestPort.DigestValue digest(String reference) {
        int colon=reference.indexOf(':');
        return new KeyedDigestPort.DigestValue(reference.substring(colon+1),reference.substring(0,colon));
    }
}
