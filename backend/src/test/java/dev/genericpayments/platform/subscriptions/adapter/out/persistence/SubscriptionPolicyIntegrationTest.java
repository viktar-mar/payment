package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.genericpayments.platform.catalog.adapter.out.persistence.PricePersistenceAdapter;
import dev.genericpayments.platform.catalog.adapter.out.persistence.ProductPersistenceAdapter;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.adapter.out.persistence.PaymentAccountPersistenceAdapter;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.adapter.out.persistence.SitePersistenceAdapter;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort;
import dev.genericpayments.platform.subscriptions.domain.PlanTransition;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class SubscriptionPolicyIntegrationTest extends PostgresIntegrationTest {
    @Autowired SubscriptionPolicyPort policies;
    @Autowired PaymentAccountPersistenceAdapter accounts;
    @Autowired SitePersistenceAdapter sites;
    @Autowired ProductPersistenceAdapter products;
    @Autowired PricePersistenceAdapter prices;
    @Autowired JdbcTemplate jdbc;
    Site site;
    Product product;

    @BeforeEach
    void fixture() {
        clearDatabase();
        site = site("subscription-site");
        product = product(site.id(), "pro-plan");
    }

    @Test
    void missingPolicyUsesDisabledDefaultsAndUpdatesRequireCurrentVersion() {
        assertThat(policies.load(site.id())).isEqualTo(SubscriptionPolicy.defaults(site.id()));
        var initial = configured(site.id(), 0, 3, Set.of("https://shop.example/billing"));
        var saved = policies.save(initial, 0);

        assertThat(saved.version()).isEqualTo(1);
        assertThat(policies.load(site.id())).isEqualTo(saved);
        assertThatThrownBy(() -> policies.save(initial, 0)).isInstanceOf(OptimisticLockingFailureException.class);
        var changed = policies.save(configured(site.id(), 1, 0, Set.of()), 1);
        assertThat(changed.version()).isEqualTo(2);
        assertThat(changed.graceDays()).isZero();
        assertThat(saved.graceDays()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://shop.example/billing", "https://user:password@shop.example/billing",
        "https://shop.example/billing#fragment", "//shop.example/billing", "http://127.0.0.1:3000/billing",
        "https://shop.example/billing%0d%0aLocation:evil"})
    void unsafeOrNonLocalReturnUrlIsRejectedWithoutWritingPolicy(String url) {
        assertThatThrownBy(() -> policies.save(configured(site.id(), 0, 3, Set.of(url)), 0))
            .isInstanceOf(DomainValidationException.class);
        assertThat(policies.load(site.id()).version()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"http://127.0.0.1:3000/billing?screen=subscriptions","http://localhost:3000/billing","http://[::1]:3000/billing"})
    void onlyLocalProfileAllowsHttpLoopbackAndPreservesExactConfiguredUrl(String url) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        var local = new SubscriptionPolicyPersistenceAdapter(jdbc, prices, products, environment);

        assertThat(local.save(configured(site.id(), 0, 3, Set.of(url)), 0).portalReturnUrls()).containsExactly(url);
        assertThatThrownBy(() -> local.save(configured(site.id(), 1, 3, Set.of("http://shop.example/billing")), 1))
            .isInstanceOf(DomainValidationException.class);
        assertThat(policies.load(site.id()).version()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings={"http://localhost.evil/billing","http://127.1/billing","http://127.0.0.2/billing","http://2130706433/billing","http://user@localhost/billing","http://localhost:0/billing","http://localhost/billing#x","http://localhost/billing%0a","https://shop.example/billing?subscriptionPortal=simulated","https://shop.example/billing?operationId=1"})
    void localPolicyRejectsUnsafeOrMarkedManagerReturns(String url) {
        var environment=new MockEnvironment();environment.setActiveProfiles("local");
        var local=new SubscriptionPolicyPersistenceAdapter(jdbc,prices,products,environment);
        assertThatThrownBy(()->local.save(configured(site.id(),0,3,Set.of(url)),0)).isInstanceOf(DomainValidationException.class);
        assertThat(policies.load(site.id()).version()).isZero();
    }

    @Test
    void transitionChangesInvalidateEarlierPolicyVersionAndRemainProductScoped() {
        var from = price(product, "pro-monthly", "EUR", true);
        var to = price(product, "premium-monthly", "EUR", true);
        policies.save(configured(site.id(), 0, 3, Set.of()), 0);
        policies.saveTransition(new PlanTransition(site.id(), product.id(), from.id(), to.id(),
            PlanTransition.Direction.UPGRADE, true, 1));

        assertThat(policies.load(site.id()).version()).isEqualTo(2);
        var transition = policies.transition(site.id(), product.id(), from.id(), to.id()).orElseThrow();
        assertThat(transition.direction()).isEqualTo(PlanTransition.Direction.UPGRADE);
        assertThat(transition.immediateAllowed()).isTrue();
        assertThat(transition.policyVersion()).isEqualTo(2);
        assertThat(policies.transitions(site.id(), product.id())).containsExactly(transition);
        assertThat(policies.transition(site.id(), new ProductId(UUID.randomUUID()), from.id(), to.id())).isEmpty();
        assertThatThrownBy(() -> policies.saveTransition(new PlanTransition(site.id(), product.id(), from.id(), to.id(),
            PlanTransition.Direction.DOWNGRADE, false, 1))).isInstanceOf(OptimisticLockingFailureException.class);
        policies.saveTransition(new PlanTransition(site.id(), product.id(), from.id(), to.id(),
            PlanTransition.Direction.DOWNGRADE, false, 2));
        assertThat(policies.transitions(site.id(), product.id())).hasSize(1);
        assertThat(policies.transition(site.id(), product.id(), from.id(), to.id()).orElseThrow().direction())
            .isEqualTo(PlanTransition.Direction.DOWNGRADE);
        assertThat(policies.load(site.id()).version()).isEqualTo(3);
    }

    @Test
    void transitionsRejectForeignProductSiteCurrencyAndUnpublishedPrice() {
        var from = price(product, "pro-monthly", "EUR", true);
        var otherProduct = product(site.id(), "other-plan");
        var otherSite = site("other-site");
        var foreignProduct = product(otherSite.id(), "foreign-plan");
        for (Price target : new Price[] {price(otherProduct, "other-monthly", "EUR", true),
                price(foreignProduct, "foreign-monthly", "EUR", true),
                price(product, "dollars-monthly", "USD", true), price(product, "draft-monthly", "EUR", false)}) {
            assertThatThrownBy(() -> policies.saveTransition(new PlanTransition(site.id(), product.id(), from.id(),
                target.id(), PlanTransition.Direction.UPGRADE, true, 0))).isInstanceOf(DomainValidationException.class);
        }
        assertThat(policies.transitions(site.id(), product.id())).isEmpty();
        assertThat(policies.load(site.id()).version()).isZero();
    }

    @Test
    void compositeDatabaseConstraintRejectsForeignProductPrice() {
        var from = price(product, "pro-monthly", "EUR", true);
        var other = price(product(site.id(), "other-plan"), "other-monthly", "EUR", true);
        policies.save(configured(site.id(), 0, 3, Set.of()), 0);

        assertThatThrownBy(() -> jdbc.update("""
            insert into subscription_plan_transitions
              (site_id,product_id,from_price_id,to_price_id,direction,immediate_allowed,policy_version)
            values (?,?,?,?,'UPGRADE',true,1)
            """, site.id().value(), product.id().value(), from.id().value(), other.id().value()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void previouslyPublishedArchivedSourceRemainsEligibleButArchivedTargetDoesNot() {
        var from = price(product, "legacy-monthly", "EUR", true);
        var to = price(product, "current-monthly", "EUR", true);
        jdbc.update("update prices set status='ARCHIVED' where id=?", from.id().value());

        policies.saveTransition(new PlanTransition(site.id(), product.id(), from.id(), to.id(),
            PlanTransition.Direction.UPGRADE, true, 0));

        assertThat(policies.transition(site.id(), product.id(), from.id(), to.id())).isPresent();
        assertThatThrownBy(() -> policies.saveTransition(new PlanTransition(site.id(), product.id(), to.id(), from.id(),
            PlanTransition.Direction.DOWNGRADE, false, 1))).isInstanceOf(DomainValidationException.class);
        assertThat(policies.load(site.id()).version()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount_minor=2999", "price_version=2", "billing_interval='YEAR'", "trial_days=0",
        "trial_payment_method_policy='OPTIONAL_DURING_TRIAL'", "tax_mode='INCLUSIVE'", "tax_code='txcd_10000000'"})
    void publishedPriceTermsCannotBeMutatedInDatabase(String assignment) {
        var published = price(product, "pro-monthly", "EUR", true);

        assertThatThrownBy(() -> jdbc.update("update prices set " + assignment + " where id=?", published.id().value()))
            .isInstanceOf(DataIntegrityViolationException.class);
        var reloaded = prices.findById(published.id()).orElseThrow();
        assertThat(reloaded.money()).isEqualTo(Money.of(1999, "EUR"));
        assertThat(reloaded.version()).isEqualTo(1);
        assertThat(reloaded.billingInterval()).isEqualTo(BillingInterval.MONTH);
        assertThat(reloaded.trialDays()).isEqualTo(14);
        assertThat(reloaded.trialPaymentMethodPolicy()).isEqualTo(TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);
    }

    @Test
    void archivalCannotMakePreviouslyPublishedTermsEditable() {
        var published = price(product, "pro-monthly", "EUR", true);
        jdbc.update("update prices set status='ARCHIVED' where id=?", published.id().value());

        assertThatThrownBy(() -> jdbc.update("update prices set trial_days=0 where id=?", published.id().value()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update prices set status='DRAFT',published_at=null where id=?", published.id().value()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void additiveMigrationKeepsLegacyPublishedPriceTermsAndAddsOptionalDefault() {
        var published = price(product, "pro-monthly", "EUR", true);
        String schema = "subscription_migration_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = jdbc.getDataSource();
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("12").load().migrate();
            jdbc.update("insert into " + schema + ".payment_accounts select * from payment_accounts where id=?", site.paymentAccountId().value());
            jdbc.update("insert into " + schema + ".sites select * from sites where id=?", site.id().value());
            jdbc.update("insert into " + schema + ".products select * from products where id=?", product.id().value());
            String legacyColumns = "id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,"
                + "billing_interval,trial_days,tax_mode,tax_code,status,published_at,created_at,updated_at,version";
            jdbc.update("insert into " + schema + ".prices (" + legacyColumns + ") select " + legacyColumns
                + " from prices where id=?", published.id().value());
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

            var row = jdbc.queryForMap("select amount_minor,price_version,billing_interval,trial_days,trial_payment_method_policy"
                + " from " + schema + ".prices where id=?", published.id().value());
            assertThat(((Number) row.get("amount_minor")).longValue()).isEqualTo(1999);
            assertThat(((Number) row.get("price_version")).intValue()).isEqualTo(1);
            assertThat(row.get("billing_interval")).isEqualTo("MONTH");
            assertThat(((Number) row.get("trial_days")).intValue()).isEqualTo(14);
            assertThat(row.get("trial_payment_method_policy")).isEqualTo("OPTIONAL_DURING_TRIAL");
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private Site site(String key) {
        var account = accounts.save(PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), key + "-account",
            PaymentProviderType.FAKE, PaymentAccountMode.SANDBOX, key + "-external", "vault://fake"));
        return sites.save(Site.create(new SiteId(UUID.randomUUID()), SiteKey.of(key), key, account.id(),
            Set.of(URI.create("https://shop.example")), Set.of(AuthenticationMode.SITE_TOKEN), PaymentMethodPolicy.automatic()));
    }

    private Product product(SiteId owner, String key) {
        return products.save(Product.create(new ProductId(UUID.randomUUID()), owner, ProductKey.of(key), key, null, INITIAL_TIME));
    }

    private Price price(Product owner, String key, String currency, boolean publish) {
        var draft = Price.draft(new PriceId(UUID.randomUUID()), owner.siteId(), owner.id(), PriceKey.of(key), 1,
            PriceType.SUBSCRIPTION, Money.of(1999, currency), BillingInterval.MONTH, 14, TaxMode.NONE, null, INITIAL_TIME)
            .withTrialPaymentMethodPolicy(TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);
        return prices.save(publish ? draft.publish(INITIAL_TIME) : draft);
    }

    private SubscriptionPolicy configured(SiteId owner, long version, int graceDays, Set<String> urls) {
        return new SubscriptionPolicy(owner, version, true, graceDays, false, !urls.isEmpty(), false, urls);
    }
}
