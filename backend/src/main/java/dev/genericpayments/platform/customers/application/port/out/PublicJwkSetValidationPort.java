package dev.genericpayments.platform.customers.application.port.out;

import java.util.List;

public interface PublicJwkSetValidationPort {
    Validated validate(String publicJwkSetJson);

    record Validated(IdentityProviderConfigurationStore.PublicJwkSet material,List<KeyMetadata> keys) {
        public Validated { keys=List.copyOf(keys); }
        @Override public String toString() { return "ValidatedPublicKeys[redacted]"; }
    }
    record KeyMetadata(String kid,String kty,String algorithm,String fingerprint) {}
}
