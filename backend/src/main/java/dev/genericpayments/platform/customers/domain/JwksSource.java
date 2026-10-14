package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.net.URI;

public sealed interface JwksSource permits JwksSource.Remote, JwksSource.StaticPublicJwkSet {
    record Remote(URI uri) implements JwksSource {
        public Remote {
            if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw new DomainValidationException("remote JWKS requires an HTTPS URL without user-info or fragments");
            }
        }

        @Override public String toString() { return "RemoteJwks[redacted]"; }
    }

    /** References public keys validated and stored by the configuration adapter. */
    record StaticPublicJwkSet(String publicJwkSetRef) implements JwksSource {
        public StaticPublicJwkSet {
            publicJwkSetRef = CustomerDomainRules.reference(publicJwkSetRef, "public JWK set");
        }

        @Override public String toString() { return "StaticPublicJwkSet[redacted]"; }
    }
}
