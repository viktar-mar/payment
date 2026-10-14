package dev.genericpayments.platform.sites.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.in.CreateSiteCommand;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.application.port.out.SaveSitePort;
import dev.genericpayments.platform.sites.domain.AuthenticationMode;
import dev.genericpayments.platform.sites.domain.PaymentMethodPolicy;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SiteServiceTest {
    private static final PaymentAccountId ACCOUNT_ID = new PaymentAccountId(UUID.randomUUID());

    @Test
    void refusesMissingPaymentAccountWithoutSavingSite() {
        InMemorySitePort sites = new InMemorySitePort();
        var missing = new LoadPaymentAccountPort() {
            @Override public Optional<PaymentAccount> findById(PaymentAccountId id) { return Optional.empty(); }
            @Override public Optional<PaymentAccount> findByKey(String key) { return Optional.empty(); }
        };
        SiteService service = new SiteService(sites, missing);

        assertThatThrownBy(() -> service.create(command("demo")))
            .isInstanceOf(DomainValidationException.class)
            .hasMessage("payment account does not exist");
        assertThat(sites.findAll()).isEmpty();
    }

    @Test
    void refusesDuplicateSiteKey() {
        InMemorySitePort sites = new InMemorySitePort();
        sites.save(existingSite("docs"));
        SiteService service = new SiteService(sites, activeAccountPort());

        assertThatThrownBy(() -> service.create(command("docs")))
            .isInstanceOf(DomainValidationException.class)
            .hasMessage("site key already exists: docs");
    }

    @Test
    void refusesDisabledPaymentAccount() {
        SiteService service = new SiteService(new InMemorySitePort(), disabledAccountPort());

        assertThatThrownBy(() -> service.create(command("demo")))
            .isInstanceOf(DomainValidationException.class)
            .hasMessageContaining("payment account is not active");
    }

    @Test
    void savesSiteForActivePaymentAccountAndListsSavedSites() {
        InMemorySitePort sites = new InMemorySitePort();
        SiteService service = new SiteService(sites, activeAccountPort());

        Site created = service.create(command("demo"));

        assertThat(created.key()).isEqualTo(SiteKey.of("demo"));
        assertThat(service.list()).containsExactly(created);
    }

    private CreateSiteCommand command(String key) {
        return new CreateSiteCommand(key, "Demo", ACCOUNT_ID,
            Set.of(URI.create("https://demo.example.com")), Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.automatic());
    }

    private Site existingSite(String key) {
        return Site.create(new SiteId(UUID.randomUUID()), SiteKey.of(key), "Docs", ACCOUNT_ID,
            Set.of(URI.create("https://docs.example.com")), Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.automatic());
    }

    private LoadPaymentAccountPort activeAccountPort() {
        PaymentAccount account = account();
        return new LoadPaymentAccountPort() {
            @Override public Optional<PaymentAccount> findById(PaymentAccountId id) {
                return account.id().equals(id) ? Optional.of(account) : Optional.empty();
            }
            @Override public Optional<PaymentAccount> findByKey(String key) { return Optional.empty(); }
        };
    }

    private LoadPaymentAccountPort disabledAccountPort() {
        PaymentAccount account = account().disable();
        return new LoadPaymentAccountPort() {
            @Override public Optional<PaymentAccount> findById(PaymentAccountId id) {
                return account.id().equals(id) ? Optional.of(account) : Optional.empty();
            }
            @Override public Optional<PaymentAccount> findByKey(String key) { return Optional.empty(); }
        };
    }

    private PaymentAccount account() {
        return PaymentAccount.create(ACCOUNT_ID, "fake-account", PaymentProviderType.FAKE,
            PaymentAccountMode.SANDBOX, "fake_123", "env:FAKE_KEY");
    }

    private static final class InMemorySitePort implements LoadSitePort, SaveSitePort {
        private final List<Site> sites = new ArrayList<>();

        @Override public Optional<Site> findById(SiteId id) {
            return sites.stream().filter(site -> site.id().equals(id)).findFirst();
        }
        @Override public Optional<Site> findByKey(SiteKey key) {
            return sites.stream().filter(site -> site.key().equals(key)).findFirst();
        }
        @Override public boolean existsByKey(SiteKey key) { return findByKey(key).isPresent(); }
        @Override public List<Site> findAll() { return List.copyOf(sites); }
        @Override public Site save(Site site) { sites.add(site); return site; }
    }
}
