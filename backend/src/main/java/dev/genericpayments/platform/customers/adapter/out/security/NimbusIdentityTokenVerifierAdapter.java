package dev.genericpayments.platform.customers.adapter.out.security;

import dev.genericpayments.platform.customers.application.model.VerifiedIdentityEvidence;
import dev.genericpayments.platform.customers.application.port.out.IdentityTokenVerifierPort;
import dev.genericpayments.platform.customers.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

public final class NimbusIdentityTokenVerifierAdapter implements IdentityTokenVerifierPort {
    private final SafeJwksClient keys;
    private final CustomerEmailNormalizer emails;
    public NimbusIdentityTokenVerifierAdapter(SafeJwksClient keys,CustomerEmailNormalizer emails) { this.keys=keys; this.emails=emails; }
    @Override public VerifiedIdentityEvidence verifyOidc(IdentityProviderConfiguration configuration,String token,String expectedNonce,Instant now) {
        return verify(configuration,token,expectedNonce,now,true);
    }
    @Override public VerifiedIdentityEvidence verifySiteToken(IdentityProviderConfiguration configuration,String token,Instant now) {
        return verify(configuration,token,null,now,false);
    }
    private VerifiedIdentityEvidence verify(IdentityProviderConfiguration config,String token,String expectedNonce,Instant now,boolean oidc) {
        try {
            if(config==null || now==null || config.status()!=IdentityProviderStatus.ACTIVE || token==null || token.length()>16384
                || oidc&&config.mode()!=CustomerAuthenticationMode.OIDC_TOKEN || !oidc&&config.mode()!=CustomerAuthenticationMode.SITE_TOKEN)
                throw CustomerAuthenticationFailure.failed();
            String issuer; Set<String> audiences; Set<String> algorithms;
            IdentityProviderConfiguration.VerifiedEmailClaimMapping mapping=null;
            if(config.configuration() instanceof IdentityProviderConfiguration.OidcTokenConfiguration c) {
                issuer=c.issuer(); audiences=c.audiences(); algorithms=c.allowedAlgorithms(); mapping=c.verifiedEmailClaims();
            } else if(config.configuration() instanceof IdentityProviderConfiguration.SiteTokenConfiguration c) {
                issuer=c.issuer(); audiences=c.audiences(); algorithms=c.allowedAlgorithms();
            } else throw CustomerAuthenticationFailure.failed();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=3 || parts[2].isEmpty()) throw CustomerAuthenticationFailure.failed();
            var header=IdentityJson.object(decode(parts[0])); var claims=IdentityJson.object(decode(parts[1]));
            if(!Set.of("alg","kid","typ").containsAll(header.keySet())) throw CustomerAuthenticationFailure.failed();
            String algorithm=boundedText(header.get("alg"),16); String kid=boundedText(header.get("kid"),128);
            if(!Set.of("RS256","ES256").contains(algorithm) || !algorithms.contains(algorithm)) throw CustomerAuthenticationFailure.failed();
            if(header.containsKey("typ")&&!(header.get("typ") instanceof String)) throw CustomerAuthenticationFailure.failed();
            if(!issuer.equals(claims.get("iss"))) throw CustomerAuthenticationFailure.failed();
            List<String> presentedAudiences=audiences(claims.get("aud"));
            if(presentedAudiences.stream().noneMatch(audiences::contains)) throw CustomerAuthenticationFailure.failed();
            if(oidc&&(presentedAudiences.size()>1 || claims.containsKey("azp"))) {
                String azp=boundedText(claims.get("azp"),2048);
                if(!audiences.contains(azp) || !presentedAudiences.contains(azp)) throw CustomerAuthenticationFailure.failed();
            }
            String subject=boundedText(claims.get("sub"),2048);
            Instant issued=timestamp(claims,"iat",true),expiry=timestamp(claims,"exp",true),notBefore=timestamp(claims,"nbf",false);
            if(issued.isAfter(now.plusSeconds(60)) || expiry.isBefore(now.minusSeconds(60)) || !expiry.isAfter(issued)
                || notBefore!=null&&(notBefore.isAfter(now.plusSeconds(60)) || notBefore.isAfter(expiry)))
                throw CustomerAuthenticationFailure.failed();
            Optional<String> tokenId=Optional.empty();
            if(oidc) {
                String nonce=boundedText(claims.get("nonce"),2048); String expected=boundedText(expectedNonce,2048);
                if(!MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8))) throw CustomerAuthenticationFailure.failed();
            } else {
                tokenId=Optional.of(boundedText(claims.get("jti"),2048));
                if(Duration.between(issued,expiry).compareTo(Duration.ofMinutes(5))>0) throw CustomerAuthenticationFailure.failed();
            }
            Optional<String> email=Optional.empty();
            if(mapping!=null && Boolean.TRUE.equals(claims.get(mapping.verifiedClaim())) && claims.containsKey(mapping.emailClaim()))
                email=Optional.of(emails.normalize(boundedText(claims.get(mapping.emailClaim()),4096)));
            var material=keys.resolve(config,kid);
            if(!algorithm.equals(material.algorithm(kid))) throw CustomerAuthenticationFailure.failed();
            var decoder=NimbusJwtDecoder.withJwkSetUri(ValidatedJwkRestOperations.MEMORY_URI.toString())
                .restOperations(new ValidatedJwkRestOperations(material)).validateType(false)
                .jwsAlgorithms(allowed->{ allowed.clear(); allowed.add(SignatureAlgorithm.from(algorithm)); }).build();
            var timestamps=new JwtTimestampValidator(Duration.ofSeconds(60));
            timestamps.setAllowEmptyExpiryClaim(false); timestamps.setClock(Clock.fixed(now,ZoneOffset.UTC));
            decoder.setJwtValidator(timestamps);
            decoder.decode(token); // Spring-managed Nimbus validates the pinned asymmetric signature.
            return new VerifiedIdentityEvidence(subject,config.providerKey(),email,tokenId,issued,expiry);
        } catch(RuntimeException invalid) { throw CustomerAuthenticationFailure.failed(); }
    }
    private static byte[] decode(String value) {
        if(value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw CustomerAuthenticationFailure.failed();
        byte[] decoded=Base64.getUrlDecoder().decode(value);
        if(!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) throw CustomerAuthenticationFailure.failed();
        return decoded;
    }
    private static String boundedText(Object value,int length) {
        if(!(value instanceof String text) || text.isBlank() || text.length()>length
            || text.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff)) throw CustomerAuthenticationFailure.failed();
        return text;
    }
    private static List<String> audiences(Object input) {
        if(input instanceof String text) return List.of(boundedText(text,2048));
        if(!(input instanceof List<?> values) || values.isEmpty() || values.size()>20) throw CustomerAuthenticationFailure.failed();
        return values.stream().map(value->boundedText(value,2048)).toList();
    }
    private static Instant timestamp(Map<String,Object> claims,String name,boolean required) {
        Object value=claims.get(name);
        if(value==null&&!required&&!claims.containsKey(name)) return null;
        if(!(value instanceof Long)&&!(value instanceof Integer)) throw CustomerAuthenticationFailure.failed();
        return Instant.ofEpochSecond(((Number)value).longValue());
    }
    @Override public String toString() { return "NimbusIdentityTokenVerifierAdapter[redacted]"; }
}
