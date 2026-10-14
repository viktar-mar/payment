package dev.genericpayments.platform.customers.adapter.out.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.genericpayments.platform.customers.adapter.out.security.IdentitySecurityFixtures.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class NimbusIdentityTokenVerifierAdapterTest {
    private final IdentityProviderConfigurationStore store=mock(IdentityProviderConfigurationStore.class);
    private final SafeJwksClient keys=new SafeJwksClient(store,new JwksAddressPolicy(),Clock.fixed(NOW,ZoneOffset.UTC));
    private final NimbusIdentityTokenVerifierAdapter verifier=new NimbusIdentityTokenVerifierAdapter(keys,new CustomerEmailNormalizer());
    private IdentityProviderConfiguration configuration(boolean oidc) {
        var cfg=IdentitySecurityFixtures.configuration(oidc,new JwksSource.StaticPublicJwkSet("stored"),Set.of("RS256","ES256"));
        when(store.findPublicJwkSet(cfg.siteId(),cfg.providerKey())).thenReturn(Optional.of(new IdentityProviderConfigurationStore.PublicJwkSet(jwks(RSA.toPublicJWK(),EC.toPublicJWK()))));
        return cfg;
    }
    @Test void acceptsRs256AndEs256AndReturnsOnlyRedactedProviderNeutralEvidence() {
        for(var key:List.of(RSA,EC)) {
            var c=claims(); c.put("email"," PROFILE@EXAMPLE.TEST "); c.put("email_verified",true);
            var evidence=verifier.verifyOidc(configuration(true),token(key,c),"fixture-nonce",NOW);
            assertThat(evidence.canonicalSubject().equals("fixture-subject")).isTrue();
            assertThat(evidence.verifiedEmail().orElseThrow().equals("profile@example.test")).isTrue();
            assertThat(evidence.issuerKey()).isEqualTo("fixture-provider");
            assertThat(evidence.expiresAt()).isEqualTo(NOW.plusSeconds(120));
            assertThat(evidence.toString()).isEqualTo("VerifiedIdentityEvidence[redacted]");
        }
    }
    @Test void siteTokenAcceptsSignedShortLivedTokenAndNeverAssumesEmailTrust() {
        var c=claims(); c.put("email","profile@example.test"); c.put("email_verified",true);
        var evidence=verifier.verifySiteToken(configuration(false),token(RSA,c),NOW);
        assertThat(evidence.tokenId().orElseThrow().equals("fixture-jti")).isTrue();
        assertThat(evidence.verifiedEmail()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"iss","aud","sub","iat","exp"})
    void requiresMandatoryClaims(String field) { var c=claims(); c.remove(field); rejectOidc(token(RSA,c)); }
    static Stream<Arguments> invalidClaims() {
        return Stream.of(Arguments.of("iss","https://other.test"),Arguments.of("aud",List.of("other")),Arguments.of("aud",List.of()),
            Arguments.of("sub"," "),Arguments.of("sub",7), Arguments.of("sub","x".repeat(2049)),
            Arguments.of("exp",NOW.minusSeconds(61).getEpochSecond()), Arguments.of("nbf",NOW.plusSeconds(61).getEpochSecond()),
            Arguments.of("iat",NOW.plusSeconds(61).getEpochSecond()),Arguments.of("iat","bad"),
            Arguments.of("exp",NOW.minusSeconds(31).getEpochSecond()),Arguments.of("nonce","incorrect"),
            Arguments.of("azp","other"));
    }
    @ParameterizedTest(name="invalid-claim-{index}") @MethodSource("invalidClaims")
    void rejectsClaimFailures(String field,Object value) { var c=claims(); c.put(field,value); rejectOidc(token(RSA,c)); }
    @Test void appliesExactlySixtySecondsOfClockSkew() {
        var c=claims(); c.put("iat",NOW.minusSeconds(120).getEpochSecond()); c.put("exp",NOW.minusSeconds(60).getEpochSecond());
        c.put("nbf",NOW.minusSeconds(120).getEpochSecond());
        assertThat(verifier.verifyOidc(configuration(true),token(RSA,c),"fixture-nonce",NOW)).isNotNull();
        c.put("exp",NOW.minusSeconds(61).getEpochSecond()); rejectOidc(token(RSA,c));
        c=claims(); c.put("nbf",NOW.plusSeconds(60).getEpochSecond()); c.put("iat",NOW.plusSeconds(60).getEpochSecond());
        assertThat(verifier.verifyOidc(configuration(true),token(RSA,c),"fixture-nonce",NOW)).isNotNull();
    }
    @Test void multiAudienceRequiresAzpMatchingAnAcceptedAudience() {
        var c=claims(); c.put("aud",List.of("web","api")); rejectOidc(token(RSA,c));
        c.put("azp","web"); assertThat(verifier.verifyOidc(configuration(true),token(RSA,c),"fixture-nonce",NOW)).isNotNull();
        c.put("azp","api"); rejectOidc(token(RSA,c));
    }
    @Test void requiresTheExpectedNonceAndOidcMode() {
        var signed=token(RSA,claims());
        assertSafeFailure(() -> verifier.verifyOidc(configuration(true),signed,null,NOW));
        assertSafeFailure(() -> verifier.verifyOidc(configuration(false),signed,"fixture-nonce",NOW));
        assertSafeFailure(() -> verifier.verifySiteToken(configuration(true),signed,NOW));
        assertSafeFailure(() -> verifier.verifyOidc(configuration(true).disable(),signed,"fixture-nonce",NOW));
    }
    @ParameterizedTest @ValueSource(strings={"jti","iat","exp"})
    void siteTokenRequiresReplayAndLifetimeClaims(String field) { var c=claims(); c.remove(field); rejectSite(token(RSA,c)); }
    @Test void siteTokenRejectsBlankJtiLongLifetimeAndInvertedTime() {
        var c=claims(); c.put("jti"," "); rejectSite(token(RSA,c));
        c=claims(); c.put("exp",NOW.plusSeconds(271).getEpochSecond()); rejectSite(token(RSA,c));
        c=claims(); c.put("iat",NOW.plusSeconds(30).getEpochSecond()); c.put("exp",NOW.plusSeconds(20).getEpochSecond()); rejectSite(token(RSA,c));
    }
    @Test void emailRequiresBooleanVerifiedFlagAndConfiguredMapping() {
        var c=claims(); c.put("email","profile@example.test"); c.put("email_verified","true");
        assertThat(verifier.verifyOidc(configuration(true),token(RSA,c),"fixture-nonce",NOW).verifiedEmail()).isEmpty();
        c.put("email_verified",true); c.put("email","invalid"); rejectOidc(token(RSA,c));
    }
    @Test void rejectsNoneHmacAndMalformedTokens() throws Exception {
        var plain=new com.nimbusds.jwt.PlainJWT(com.nimbusds.jwt.JWTClaimsSet.parse(claims())).serialize(); rejectOidc(plain);
        var hmac=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),com.nimbusds.jwt.JWTClaimsSet.parse(claims()));
        hmac.sign(new com.nimbusds.jose.crypto.MACSigner(new byte[32])); rejectOidc(hmac.serialize());
        rejectOidc("invalid-token"); rejectOidc("x".repeat(16385));
    }
    @Test void rejectsInvalidSignaturesUnknownKidsAndConfiguredAlgorithmMismatch() {
        rejectOidc(token(rsa("rsa-key"),claims())); rejectOidc(token(rsa("unknown"),claims()));
        var cfg=IdentitySecurityFixtures.configuration(true,new JwksSource.StaticPublicJwkSet("stored"),Set.of("ES256"));
        assertSafeFailure(() -> verifier.verifyOidc(cfg,token(RSA,claims()),"fixture-nonce",NOW));
        rejectOidc(token(RSA,claims(),Map.of("kid","ec-key")));
        rejectOidc(token(RSA,claims(),Map.of("kid","")));
    }
    @Test void rejectsEmbeddedKeySourcesAndUnexpectedCriticalHeadersBeforeKeyLookup() {
        for(var header:List.of(Map.<String,Object>of("jwk",RSA.toPublicJWK().toJSONObject()),Map.<String,Object>of("jku","https://other.test/keys"),
            Map.<String,Object>of("x5u","https://other.test/cert"),Map.<String,Object>of("crit",List.of("custom"),"custom",true))) {
            rejectOidc(token(RSA,claims(),header));
        }
        verifyNoInteractions(store);
    }
    @Test void rejectsDuplicateHeaderAndClaimMembers() throws Exception {
        var signed=token(RSA,claims()); var segments=signed.split("\\.");
        String duplicate="{\"alg\":\"RS256\",\"kid\":\"rsa-key\",\"kid\":\"rsa-key\"}";
        rejectOidc(Base64.getUrlEncoder().withoutPadding().encodeToString(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+segments[1]+"."+segments[2]);
        String payload=com.nimbusds.jose.util.JSONObjectUtils.toJSONString(claims());
        payload=payload.substring(0,payload.length()-1)+",\"sub\":\"duplicate-subject\"}";
        var duplicateClaims=new com.nimbusds.jose.JWSObject(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID("rsa-key").build(),new com.nimbusds.jose.Payload(payload));
        duplicateClaims.sign(new com.nimbusds.jose.crypto.RSASSASigner(RSA));
        rejectOidc(duplicateClaims.serialize());
    }
    private void rejectOidc(String raw) { assertSafeFailure(() -> verifier.verifyOidc(configuration(true),raw,"fixture-nonce",NOW)); }
    private void rejectSite(String raw) { assertSafeFailure(() -> verifier.verifySiteToken(configuration(false),raw,NOW)); }
    static void assertSafeFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(PaymentApplicationException.class).hasMessage("Authentication failed").hasNoCause();
    }
}
