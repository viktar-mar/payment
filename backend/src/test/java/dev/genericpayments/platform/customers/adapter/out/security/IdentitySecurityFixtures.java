package dev.genericpayments.platform.customers.adapter.out.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.util.*;

final class IdentitySecurityFixtures {
    static final Instant NOW=Instant.parse("2026-10-10T12:00:00Z");
    static final RSAKey RSA=rsa("rsa-key");
    static final ECKey EC=ec("ec-key");
    static RSAKey rsa(String kid) { try { return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); } catch(Exception e) { throw new IllegalStateException("Fixture generation failed"); } }
    static ECKey ec(String kid) { try { return new ECKeyGenerator(Curve.P_256).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate(); } catch(Exception e) { throw new IllegalStateException("Fixture generation failed"); } }
    static String jwks(JWK... keys) { return new JWKSet(List.of(keys)).toString(false); }
    static Map<String,Object> claims() {
        var result=new HashMap<String,Object>();
        result.put("iss","https://issuer.test"); result.put("aud",List.of("web")); result.put("sub","fixture-subject");
        result.put("iat",NOW.minusSeconds(30).getEpochSecond()); result.put("exp",NOW.plusSeconds(120).getEpochSecond());
        result.put("nbf",NOW.minusSeconds(30).getEpochSecond()); result.put("nonce","fixture-nonce"); result.put("jti","fixture-jti");
        return result;
    }
    static String token(JWK key, Map<String,Object> claims) { return token(key,claims,Map.of()); }
    static String token(JWK key, Map<String,Object> claims, Map<String,Object> headers) {
        try {
            var header=new HashMap<String,Object>(); header.put("alg",key instanceof RSAKey?"RS256":"ES256"); header.put("kid",key.getKeyID()); header.putAll(headers);
            // Sign raw JSON so malformed claim types reach our boundary instead of Nimbus normalizing them first.
            var jwt=new JWSObject(JWSHeader.parse(header),new Payload(com.nimbusds.jose.util.JSONObjectUtils.toJSONString(claims)));
            jwt.sign(key instanceof RSAKey rsa?new RSASSASigner(rsa):new ECDSASigner((ECKey)key)); return jwt.serialize();
        } catch(Exception e) { throw new IllegalStateException("Fixture signing failed"); }
    }
    static IdentityProviderConfiguration configuration(boolean oidc, JwksSource source, Set<String> algorithms) {
        var cfg=oidc ? new IdentityProviderConfiguration.OidcTokenConfiguration("https://issuer.test",Set.of("web"),source,algorithms,
                new IdentityProviderConfiguration.VerifiedEmailClaimMapping("email","email_verified"))
            : new IdentityProviderConfiguration.SiteTokenConfiguration("https://issuer.test",Set.of("web"),source,algorithms);
        return IdentityProviderConfiguration.create(new SiteId(UUID.randomUUID()),"fixture-provider",cfg);
    }
    private IdentitySecurityFixtures() {}
}
