package dev.genericpayments.platform.sites.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.genericpayments.platform.paymentaccounts.adapter.out.persistence.PaymentAccountPersistenceAdapter;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.sites.domain.AuthenticationMode;
import dev.genericpayments.platform.sites.domain.PaymentMethod;
import dev.genericpayments.platform.sites.domain.PaymentMethodPolicy;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SitePersistenceAdapterTest extends PostgresIntegrationTest {
    @Autowired
    private PaymentAccountPersistenceAdapter accounts;

    @Autowired
    private SitePersistenceAdapter sites;

    @Autowired
    private SiteJpaRepository repository;

    @BeforeEach
    void prepareDatabase() {
        clearDatabase();
    }

    @Test
    void roundTripsSiteCollectionsAndPolicies() {
        PaymentAccount account = accounts.save(activeFakeAccount());
        Site original = site(account.id(), "shop-main", "Shop Main",
            Set.of(URI.create("https://shop.example.com"), URI.create("http://localhost:3000")),
            Set.of(AuthenticationMode.OIDC_TOKEN, AuthenticationMode.EMAIL_OTP),
            PaymentMethodPolicy.allow(Set.of(PaymentMethod.CARD, PaymentMethod.PAYPAL)));

        Site saved = sites.save(original);

        assertThat(saved).isEqualTo(original);
        assertThat(sites.findById(original.id())).contains(original);
        assertThat(sites.findByKey(SiteKey.of("SHOP-MAIN"))).contains(original);
        assertThat(sites.existsByKey(SiteKey.of("shop-main"))).isTrue();
        assertThat(sites.findAll()).containsExactly(original);
    }

    @Test
    void persistsAutomaticPolicyWithoutAllowedPaymentMethods() {
        PaymentAccount account = accounts.save(activeFakeAccount());
        Site original = site(account.id(), "shop-auto", "Shop Automatic",
            Set.of(URI.create("https://automatic.example.com")), Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.automatic());

        sites.save(original);

        assertThat(sites.findById(original.id())).contains(original);
        assertThat(repository.findById(original.id().value()).orElseThrow().allowedPaymentMethods()).isEmpty();
    }

    @Test
    void updatesSiteTimestampAndPreservesCreationTimestamp() {
        PaymentAccount account = accounts.save(activeFakeAccount());
        Site original = sites.save(site(account.id(), "shop-update", "Shop Update",
            Set.of(URI.create("https://update.example.com")), Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.automatic()));
        SiteJpaEntity initial = repository.findById(original.id().value()).orElseThrow();
        Instant createdAt = initial.createdAt();
        long initialVersion = initial.version();
        TestClock.set(INITIAL_TIME.plusSeconds(60));
        Site disabled = original.disable();

        assertThat(sites.save(disabled)).isEqualTo(disabled);
        SiteJpaEntity stored = repository.findById(disabled.id().value()).orElseThrow();
        assertThat(stored.createdAt()).isEqualTo(createdAt);
        assertThat(stored.updatedAt()).isEqualTo(INITIAL_TIME.plusSeconds(60));
        assertThat(stored.version()).isGreaterThan(initialVersion);
    }

    private static PaymentAccount activeFakeAccount() {
        return PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), "checkout-fake",
            PaymentProviderType.FAKE, PaymentAccountMode.SANDBOX, "fake-account-123", "vault://fake/checkout");
    }

    private static Site site(PaymentAccountId accountId, String key, String displayName, Set<URI> origins,
                             Set<AuthenticationMode> authenticationModes, PaymentMethodPolicy policy) {
        return Site.create(new SiteId(UUID.randomUUID()), SiteKey.of(key), displayName, accountId, origins,
            authenticationModes, policy);
    }
}
