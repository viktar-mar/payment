package dev.genericpayments.platform.customers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.customers.domain.IdentityProviderConfiguration.EmailOtpConfiguration;
import dev.genericpayments.platform.customers.domain.IdentityProviderConfiguration.OidcTokenConfiguration;
import dev.genericpayments.platform.customers.domain.IdentityProviderConfiguration.SiteTokenConfiguration;
import dev.genericpayments.platform.customers.domain.IdentityProviderConfiguration.VerifiedEmailClaimMapping;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityProviderConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings={"smtp://host","name@example.test","secret password","bad_ref","sender\nsecret"})
    void rejectsMailSenderValuesThatAreNotBoundedDeploymentIdentifiers(String sender) {
        assertThatThrownBy(() -> new EmailOtpConfiguration(sender,"otp-default")).isInstanceOf(DomainValidationException.class);
    }
    private static final SiteId SITE_ID = new SiteId(UUID.randomUUID());
    private static final JwksSource REMOTE = new JwksSource.Remote(URI.create("https://issuer.example/jwks"));

    @Test
    void oidcConfigurationCopiesAndNormalizesAudiences() {
        Set<String> audiences = new HashSet<>(Set.of(" app ", "app", "mobile"));
        Set<String> algorithms = new HashSet<>(Set.of("RS256", "ES256"));
        var mapping = new VerifiedEmailClaimMapping("email", "email_verified");
        var settings = new OidcTokenConfiguration("https://issuer.example", audiences, REMOTE, algorithms, mapping);
        var configuration = IdentityProviderConfiguration.create(SITE_ID, "Google", settings);
        audiences.clear();
        algorithms.clear();

        assertThat(configuration.providerKey()).isEqualTo("google");
        assertThat(configuration.siteId()).isEqualTo(SITE_ID);
        assertThat(configuration.mode()).isEqualTo(CustomerAuthenticationMode.OIDC_TOKEN);
        assertThat(configuration.status()).isEqualTo(IdentityProviderStatus.ACTIVE);
        assertThat(configuration.version()).isZero();
        assertThat(settings.issuer()).isEqualTo("https://issuer.example");
        assertThat(settings.audiences()).containsExactlyInAnyOrder("app", "mobile");
        assertThat(settings.allowedAlgorithms()).containsExactlyInAnyOrder("RS256", "ES256");
        assertThat(settings.verifiedEmailClaims()).isEqualTo(mapping);
        assertThat(settings.jwksSource()).isSameAs(REMOTE);
        assertThatThrownBy(() -> settings.audiences().add("other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> settings.allowedAlgorithms().add("none")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void supportsSiteTokenWithStaticPublicKeyReferenceAndEmailOtpSettings() {
        var staticKeys = new JwksSource.StaticPublicJwkSet("public-jwk-set-ref");
        var site = new SiteTokenConfiguration("site-issuer", Set.of(" app "), staticKeys, Set.of("ES256"));
        var email = new EmailOtpConfiguration("sender-config-ref", "email-login");
        assertThat(IdentityProviderConfiguration.create(SITE_ID, "site-token", site).mode())
            .isEqualTo(CustomerAuthenticationMode.SITE_TOKEN);
        assertThat(site.audiences()).containsExactly("app");
        assertThat(site.jwksSource()).isSameAs(staticKeys);
        assertThat(staticKeys.publicJwkSetRef()).isEqualTo("public-jwk-set-ref");
        assertThat(IdentityProviderConfiguration.create(SITE_ID, "email-otp", email).mode())
            .isEqualTo(CustomerAuthenticationMode.EMAIL_OTP);
        assertThat(email.senderRef()).isEqualTo("sender-config-ref");
        assertThat(email.templateKey()).isEqualTo("email-login");
    }

    @Test
    void disablePreservesSiteStableKeySettingsAndOptimisticVersion() {
        var initial = IdentityProviderConfiguration.create(SITE_ID, "google", oidc(Set.of("RS256")));
        var disabled = initial.disable();
        assertThat(initial.status()).isEqualTo(IdentityProviderStatus.ACTIVE);
        assertThat(disabled.status()).isEqualTo(IdentityProviderStatus.DISABLED);
        assertThat(disabled.siteId()).isEqualTo(SITE_ID);
        assertThat(disabled.providerKey()).isEqualTo("google");
        assertThat(disabled.configuration()).isSameAs(initial.configuration());
        assertThat(disabled.version()).isZero();
        assertThat(disabled.disable()).isSameAs(disabled);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "go", "bad_key", "has space", "x/y"})
    void rejectsUnsafeProviderKeys(String key) {
        assertThatThrownBy(() -> IdentityProviderConfiguration.create(SITE_ID, key, oidc(Set.of("RS256"))))
            .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "HS256", "RS512", "rs256", " RS256 "})
    void rejectsAlgorithmsOutsideAsymmetricAllowlist(String algorithm) {
        assertThatThrownBy(() -> oidc(Set.of("RS256", algorithm))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new SiteTokenConfiguration("site", Set.of("app"), REMOTE, Set.of(algorithm)))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsEmptyOrMissingTokenSettings() {
        assertThatThrownBy(() -> oidc(Set.of())).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> oidc(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OidcTokenConfiguration("issuer", Set.of(), REMOTE, Set.of("RS256"), null))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OidcTokenConfiguration("issuer", null, REMOTE, Set.of("RS256"), null))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OidcTokenConfiguration("issuer", Set.of(" "), REMOTE, Set.of("RS256"), null))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OidcTokenConfiguration(" ", Set.of("app"), REMOTE, Set.of("RS256"), null))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new SiteTokenConfiguration("site", Set.of("app"), null, Set.of("RS256")))
            .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "\u001f", "\u0000 \t\u001f"})
    void rejectsControlOnlyOidcAudience(String audience) {
        assertThatThrownBy(() -> new OidcTokenConfiguration("issuer", Set.of("app", audience), REMOTE,
            Set.of("RS256"), null)).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "\u001f", "\u0000 \t\u001f"})
    void rejectsControlOnlySiteTokenAudience(String audience) {
        assertThatThrownBy(() -> new SiteTokenConfiguration("site", Set.of("app", audience), REMOTE,
            Set.of("RS256"))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNullAlgorithmAsDomainValidationFailure() {
        Set<String> algorithms = new HashSet<>(Set.of("RS256"));
        algorithms.add(null);
        assertThatThrownBy(() -> oidc(algorithms)).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://issuer.example/jwks", "file:///keys", "/jwks", "https://user@issuer.example/jwks",
        "https://issuer.example/jwks#fragment", "https:///jwks"})
    void rejectsUnsafeRemoteJwksLocations(String location) {
        assertThatThrownBy(() -> new JwksSource.Remote(URI.create(location))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMissingReferencesAndMetadata() {
        assertThatThrownBy(() -> new JwksSource.Remote(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new JwksSource.StaticPublicJwkSet(" ")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new EmailOtpConfiguration(" ", "email-login")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new EmailOtpConfiguration("sender-ref", "bad_template")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new VerifiedEmailClaimMapping(" ", "email_verified"))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new VerifiedEmailClaimMapping("email", null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> IdentityProviderConfiguration.create(null, "google", oidc(Set.of("RS256"))))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> IdentityProviderConfiguration.create(SITE_ID, "google", null))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdentityProviderConfiguration(SITE_ID, "google", null, oidc(Set.of("RS256")), 0))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdentityProviderConfiguration(SITE_ID, "google", IdentityProviderStatus.ACTIVE,
            oidc(Set.of("RS256")), -1)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> IdentityProviderConfiguration.create(SITE_ID, "g".repeat(64), oidc(Set.of("RS256"))))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void diagnosticsRedactProviderSettingsAndJwksReferences() {
        var remote = new JwksSource.Remote(URI.create("https://issuer.example/jwks?opaque=query"));
        var oidc = new OidcTokenConfiguration("private-issuer", Set.of("private-audience"), remote, Set.of("RS256"), null);
        var site = new SiteTokenConfiguration("private-issuer", Set.of("private-audience"), remote, Set.of("RS256"));
        var email = new EmailOtpConfiguration("sender-config-ref", "email-login");
        assertThat(IdentityProviderConfiguration.create(SITE_ID, "google", oidc).toString())
            .doesNotContain("private-issuer", "private-audience", "opaque=query");
        assertThat(oidc.toString()).doesNotContain("private-issuer", "private-audience", "opaque=query");
        assertThat(site.toString()).doesNotContain("private-issuer", "private-audience", "opaque=query");
        assertThat(email.toString()).doesNotContain("sender-config-ref", "email-login");
        assertThat(remote.toString()).doesNotContain("issuer.example", "opaque=query");
        assertThat(new JwksSource.StaticPublicJwkSet("public-jwk-set-ref").toString()).doesNotContain("public-jwk-set-ref");
        assertThat(new VerifiedEmailClaimMapping("private_email", "private_verified").toString())
            .doesNotContain("private_email", "private_verified");
    }

    private OidcTokenConfiguration oidc(Set<String> algorithms) {
        return new OidcTokenConfiguration("https://issuer.example", Set.of("app"), REMOTE, algorithms, null);
    }
}
