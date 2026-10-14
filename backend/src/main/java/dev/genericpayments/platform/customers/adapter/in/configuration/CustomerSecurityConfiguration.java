package dev.genericpayments.platform.customers.adapter.in.configuration;

import dev.genericpayments.platform.customers.adapter.out.security.*;
import dev.genericpayments.platform.customers.adapter.in.web.*;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.customers.application.port.out.PublicCustomerAuthenticationRateLimitPort;
import java.net.InetAddress;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({CustomerSecurityProperties.class,CustomerAuthenticationProperties.class,
    CustomerAuthenticationRateLimitProperties.class})
public class CustomerSecurityConfiguration {
    @Bean HmacSha256KeyedDigestAdapter customerKeyedDigestPort(CustomerSecurityProperties properties) {
        return new HmacSha256KeyedDigestAdapter(properties);
    }
    @Bean CustomerEmailNormalizer customerEmailNormalizer() { return new CustomerEmailNormalizer(); }
    @Bean JwksAddressPolicy customerJwksAddressPolicy(Environment environment,
            @Value("${payment.customer-security.local-jwks-hosts:}") String localHosts) {
        var hosts=new HashSet<String>();
        for(String host:localHosts.split(",")) if(!host.isBlank()) hosts.add(host.trim());
        return new JwksAddressPolicy(InetAddress::getAllByName,Set.copyOf(Arrays.asList(environment.getActiveProfiles())),hosts);
    }
    @Bean SafeJwksClient customerJwksClient(IdentityProviderConfigurationStore store,JwksAddressPolicy policy,Clock clock) {
        return new SafeJwksClient(store,policy,clock);
    }
    @Bean NimbusIdentityTokenVerifierAdapter customerIdentityTokenVerifierPort(SafeJwksClient client,CustomerEmailNormalizer normalizer) {
        return new NimbusIdentityTokenVerifierAdapter(client,normalizer);
    }
    @Bean PublicCustomerAuthenticationRateLimitPort customerAuthenticationRateLimitPort(Clock clock,
            CustomerAuthenticationRateLimitProperties properties) {
        return new InMemoryCustomerAuthenticationRateLimitAdapter(clock,properties.oidcBeginPerMinute(),
            properties.oidcExchangePerMinute(),properties.siteTokenExchangePerMinute(),
            properties.siteTokenLinkProofPerMinute(),properties.maxKeys());
    }
}
