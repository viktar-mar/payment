package dev.genericpayments.platform.customers.adapter.in.configuration;

import dev.genericpayments.platform.customers.application.port.out.CustomerAuthenticationLifetimePort;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.customer-authentication")
public record CustomerAuthenticationProperties(Duration oidcAttemptTtl,Duration linkProofTtl)
        implements CustomerAuthenticationLifetimePort {
    public CustomerAuthenticationProperties {
        oidcAttemptTtl=bounded(oidcAttemptTtl,"OIDC attempt TTL");
        linkProofTtl=bounded(linkProofTtl,"link proof TTL");
    }
    public CustomerAuthenticationProperties() { this(Duration.ofMinutes(5),Duration.ofMinutes(5)); }
    private static Duration bounded(Duration value,String name) {
        if(value==null) value=Duration.ofMinutes(5);
        if(value.compareTo(Duration.ofMinutes(1))<0 || value.compareTo(Duration.ofMinutes(10))>0) throw new IllegalArgumentException("Invalid "+name);
        return value;
    }
}
