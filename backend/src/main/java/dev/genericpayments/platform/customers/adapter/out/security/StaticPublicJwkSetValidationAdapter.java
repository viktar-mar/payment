package dev.genericpayments.platform.customers.adapter.out.security;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import org.springframework.stereotype.Component;

@Component
public class StaticPublicJwkSetValidationAdapter implements PublicJwkSetValidationPort {
    @Override public Validated validate(String publicJwkSetJson) {
        try {
            var validated=SafeJwksClient.validateStatic(new IdentityProviderConfigurationStore.PublicJwkSet(publicJwkSetJson));
            return new Validated(validated.publicMaterial(),validated.metadata());
        } catch(RuntimeException invalid) { throw new DomainValidationException("invalid public JWK set"); }
    }
}
