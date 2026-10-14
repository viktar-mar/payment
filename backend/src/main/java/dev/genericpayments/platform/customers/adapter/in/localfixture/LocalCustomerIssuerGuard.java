package dev.genericpayments.platform.customers.adapter.in.localfixture;

import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Unconditional guard: profile annotations alone must not silently accept production fixture settings. */
@Component
public final class LocalCustomerIssuerGuard {
    public LocalCustomerIssuerGuard(Environment environment) {
        boolean configured=environment.getProperty("payment.customer-local-issuer.enabled",Boolean.class,false)
            || !environment.getProperty("payment.customer-local-issuer.private-jwk-set","").isBlank()
            || environment.getProperty("PAYMENT_CUSTOMER_LOCAL_ISSUER_ENABLED",Boolean.class,false)
            || !environment.getProperty("PAYMENT_CUSTOMER_LOCAL_ISSUER_PRIVATE_JWK_SET","").isBlank();
        var profiles=environment.getActiveProfiles();
        if(configured && (profiles.length==0 || Arrays.stream(profiles).anyMatch(p -> !p.equals("local")&&!p.equals("test"))))
            throw new IllegalStateException("Local Customer issuer fixtures require exclusively local/test profiles");
    }
}
