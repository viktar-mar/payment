package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteKey;
import org.springframework.stereotype.Component;

@Component
final class CustomerAuthenticationPolicy {
    private final LoadSitePort sites; private final IdentityProviderConfigurationStore providers; private final EmailOtpProviderSelector selector;
    CustomerAuthenticationPolicy(LoadSitePort sites,IdentityProviderConfigurationStore providers,EmailOtpProviderSelector selector) {
        this.sites=sites; this.providers=providers; this.selector=selector;
    }
    Context load(String siteKey,String providerKey,String origin,CustomerAuthenticationMode mode) {
        try {
            String normalized=CustomerAuthenticationSupport.origin(origin);
            var site=sites.findByKey(SiteKey.of(siteKey)).orElseThrow(CustomerAuthenticationSupport::failed);
            CustomerAuthenticationSupport.active(site,mode,normalized);
            var provider=providers.find(site.id(),providerKey).orElseThrow(CustomerAuthenticationSupport::failed);
            if(provider.status()!=IdentityProviderStatus.ACTIVE || provider.mode()!=mode) throw CustomerAuthenticationSupport.failed();
            return new Context(site,provider,normalized);
        } catch(RuntimeException failure) {
            throw CustomerAuthenticationSupport.failed();
        }
    }
    Context loadEmail(String siteKey,String origin) {
        try {
            String normalized=CustomerAuthenticationSupport.origin(origin);
            var site=sites.findByKey(SiteKey.of(siteKey)).orElseThrow(CustomerAuthenticationSupport::failed);
            CustomerAuthenticationSupport.active(site,CustomerAuthenticationMode.EMAIL_OTP,normalized);
            var provider=selector.select(providers.list(site.id())).orElseThrow(CustomerAuthenticationSupport::failed);
            return new Context(site,provider,normalized);
        } catch(RuntimeException failure){throw CustomerAuthenticationSupport.failed();}
    }
    EmailContext loadEmailSite(String siteKey,String origin) {
        try {
            String normalized=CustomerAuthenticationSupport.origin(origin);
            var site=sites.findByKey(SiteKey.of(siteKey)).orElseThrow(CustomerAuthenticationSupport::failed);
            CustomerAuthenticationSupport.active(site,CustomerAuthenticationMode.EMAIL_OTP,normalized);
            return new EmailContext(site,normalized);
        } catch(RuntimeException failure){throw CustomerAuthenticationSupport.failed();}
    }
    record Context(dev.genericpayments.platform.sites.domain.Site site,IdentityProviderConfiguration provider,String origin) {}
    record EmailContext(dev.genericpayments.platform.sites.domain.Site site,String origin) {}
}
