package dev.genericpayments.platform.sites.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class SiteTest {
    private static final PaymentAccountId ACCOUNT_ID = new PaymentAccountId(UUID.randomUUID());

    @Test
    void createsAnActiveSiteWithNormalizedKeyAndOrigins() {
        Site site = Site.create(new SiteId(UUID.randomUUID()), SiteKey.of("Demo-Site"),
            "Demo Site", ACCOUNT_ID,
            Set.of(URI.create("https://demo.example.com"), URI.create("http://localhost:3000")),
            Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.allow(Set.of(PaymentMethod.CARD, PaymentMethod.PAYPAL)));

        assertThat(site.key().value()).isEqualTo("demo-site");
        assertThat(site.status()).isEqualTo(SiteStatus.ACTIVE);
        assertThat(site.allowedOrigins()).containsExactlyInAnyOrder(
            URI.create("https://demo.example.com"), URI.create("http://localhost:3000"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOrigins")
    void rejectsEachInvalidOriginIndependently(String rule, URI origin) {
        assertThatThrownBy(() -> Site.create(new SiteId(UUID.randomUUID()), SiteKey.of("demo"),
            "Demo", ACCOUNT_ID, Set.of(origin),
            Set.of(AuthenticationMode.SITE_TOKEN), PaymentMethodPolicy.automatic()))
            .isInstanceOf(DomainValidationException.class)
            .hasMessageContaining("allowed origin");
    }

    private static Stream<Arguments> invalidOrigins() {
        return Stream.of(
            Arguments.of("path", URI.create("https://example.com/checkout")),
            Arguments.of("remote-http", URI.create("http://example.com")),
            Arguments.of("query", URI.create("https://example.com?next=checkout")),
            Arguments.of("fragment", URI.create("https://example.com#checkout")),
            Arguments.of("user-info", URI.create("https://admin@example.com")));
    }

    @Test
    void rejectsEmptyPaymentMethodAllowlist() {
        assertThatThrownBy(() -> PaymentMethodPolicy.allow(Set.of()))
            .isInstanceOf(DomainValidationException.class)
            .hasMessageContaining("allowlist");
    }

    @Test
    void copiesMutableCollectionsAndReturnsImmutableSets() {
        Set<URI> origins = new java.util.HashSet<>(Set.of(URI.create("https://demo.example.com")));
        Site site = Site.create(new SiteId(UUID.randomUUID()), SiteKey.of("demo"), "Demo", ACCOUNT_ID,
            origins, Set.of(AuthenticationMode.SITE_TOKEN), PaymentMethodPolicy.automatic());

        origins.add(URI.create("http://localhost:3000"));

        assertThat(site.allowedOrigins()).containsExactly(URI.create("https://demo.example.com"));
        assertThatThrownBy(() -> site.allowedOrigins().add(URI.create("http://localhost:3000")))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
