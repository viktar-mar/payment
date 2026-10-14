package dev.genericpayments.platform.partners.adapter.in.configuration;
import dev.genericpayments.platform.partners.application.port.out.*;
import dev.genericpayments.platform.partners.application.service.SiteApiKeyService;
import dev.genericpayments.platform.partners.adapter.out.security.Argon2SiteApiKeyAdapter;
import dev.genericpayments.platform.partners.adapter.in.web.PartnerRateLimitProperties;
import dev.genericpayments.platform.payments.application.port.out.RateLimitPort;
import dev.genericpayments.platform.payments.adapter.in.web.InMemoryTokenBucketRateLimitAdapter;
import dev.genericpayments.platform.shared.application.port.out.RandomTokenPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(PartnerRateLimitProperties.class)
class PartnerConfiguration {
    @Bean SiteApiKeyHashPort siteApiKeyHashes() { return new Argon2SiteApiKeyAdapter(); }
    @Bean SiteApiKeyService siteApiKeyService(SiteApiKeyPort keys,SiteApiKeyHashPort hashes,RandomTokenPort random,LoadSitePort sites,Clock clock) { return new SiteApiKeyService(keys,hashes,random,sites,clock); }
    @Bean("partnerRateLimitPort") RateLimitPort partnerRateLimitPort(Clock clock,PartnerRateLimitProperties properties) {
        return new InMemoryTokenBucketRateLimitAdapter(clock,properties.createPerMinute(),properties.handoffPerMinute(),properties.readPerMinute(),properties.maxKeys());
    }
}
