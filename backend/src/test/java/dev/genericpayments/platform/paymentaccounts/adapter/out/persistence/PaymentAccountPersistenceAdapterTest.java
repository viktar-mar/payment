package dev.genericpayments.platform.paymentaccounts.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.sites.adapter.out.persistence.SitePersistenceAdapter;
import dev.genericpayments.platform.sites.domain.AuthenticationMode;
import dev.genericpayments.platform.sites.domain.PaymentMethodPolicy;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentAccountPersistenceAdapterTest extends PostgresIntegrationTest {
    @Autowired
    private PaymentAccountPersistenceAdapter accounts;

    @Autowired
    private PaymentAccountJpaRepository repository;

    @Autowired
    private SitePersistenceAdapter sites;

    @BeforeEach
    void clearAccounts() {
        clearDatabase();
    }

    @Test
    void roundTripsEveryPaymentAccountField() {
        PaymentAccount original = account("checkout-fake", "fake-account-123", "vault://fake/checkout");

        PaymentAccount saved = accounts.save(original);

        assertThat(saved).isEqualTo(original);
        assertThat(accounts.findById(original.id())).contains(original);
        assertThat(accounts.findByKey("checkout-fake")).contains(original);
        assertThat(repository.findById(original.id().value()).orElseThrow().createdAt())
            .isEqualTo(INITIAL_TIME);
    }

    @Test
    void updatesAccountWithoutReplacingCreationMetadata() {
        PaymentAccount original = accounts.save(account("checkout-fake", "fake-account-123", "vault://fake/checkout"));
        var createdAt = repository.findById(original.id().value()).orElseThrow().createdAt();
        TestClock.set(INITIAL_TIME.plusSeconds(60));
        PaymentAccount disabled = original.disable();

        assertThat(accounts.save(disabled)).isEqualTo(disabled);
        PaymentAccountJpaEntity stored = repository.findById(disabled.id().value()).orElseThrow();
        assertThat(stored.createdAt()).isEqualTo(createdAt);
        assertThat(stored.version()).isEqualTo(1L);
    }

    @Test
    void cleanupRemovesSitesBeforeReferencedPaymentAccounts() {
        PaymentAccount paymentAccount = accounts.save(account("checkout-fake", "fake-account-123", "vault://fake/checkout"));
        sites.save(Site.create(new SiteId(UUID.randomUUID()), SiteKey.of("checkout-site"), "Checkout",
            paymentAccount.id(), Set.of(URI.create("https://checkout.example.com")),
            Set.of(AuthenticationMode.SITE_TOKEN), PaymentMethodPolicy.automatic()));

        assertThatCode(this::clearAccounts).doesNotThrowAnyException();
        assertThat(repository.count()).isZero();
    }

    private static PaymentAccount account(String key, String externalAccountId, String credentialsRef) {
        return PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), key, PaymentProviderType.FAKE,
            PaymentAccountMode.SANDBOX, externalAccountId, credentialsRef);
    }
}
