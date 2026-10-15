package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;

import com.stripe.StripeClient;
import dev.genericpayments.platform.catalog.application.port.in.PublishPriceUseCase;
import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.io.IOException;
import java.util.UUID;
import dev.genericpayments.platform.catalog.application.model.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ActiveProfiles("test")
@TestPropertySource(properties = {
    "payment.stripe.sandbox.enabled=true",
    "payment.stripe.sandbox.secret-key=sk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "payment.stripe.sandbox.publishable-key=pk_test_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
    "payment.stripe.sandbox.webhook-secret=whsec_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"})
@Import(StripeCatalogProviderAdapterTest.FixtureConfiguration.class)
class StripeCatalogProviderAdapterTest extends CheckoutTestFixture {
    @Autowired PublishPriceUseCase publication;
    @Autowired ProviderCatalogMappingPort mappings;
    @Autowired LoadPricePort loadPrices;
    @Autowired FixtureFactory factory;
    @Autowired StripeCatalogProviderAdapter stripeCatalog;
    @Autowired LoadProductPort loadProducts;
    @Autowired dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry subscriptions;
    StripeHttpFixture http;

    @BeforeEach void stripe() throws IOException {
        http = new StripeHttpFixture();
        factory.http = http;
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
    }
    @AfterEach void closeHttp() {
        http.close();
        jdbc.execute("drop trigger if exists reject_catalog_publish on prices");
        jdbc.execute("drop function if exists reject_catalog_publish()");
    }

    @Test void publishesFixedTotalWithAccountBoundSdkRequestsAndDurableReceipts() {
        var draft = catalogPrice("stripe-once", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        enqueuePublication();
        var published = publication.publish(draft.id());
        assertThat(published.status()).isEqualTo(PriceStatus.PUBLISHED);
        assertThat(mappings.findPrice(account.id(), draft.id()).orElseThrow().value()).isEqualTo("price_fixture");
        assertThat(http.calls().stream().filter(c -> c.method().equals("GET")).map(StripeHttpFixture.Call::path))
            .isNotEmpty().containsOnly("/v1/account");
        var writes = http.calls().stream().filter(c -> c.method().equals("POST")).toList();
        assertThat(writes).hasSize(2);
        assertThat(writes.get(0).path()).isEqualTo("/v1/products");
        assertThat(writes.get(0).form()).containsEntry("name", "Lifetime");
        assertThat(writes.get(1).path()).isEqualTo("/v1/prices");
        assertThat(writes.get(1).form()).containsEntry("product", "prod_fixture")
            .containsEntry("unit_amount", "4999").containsEntry("currency", "eur")
            .containsEntry("tax_behavior", "inclusive");
        assertThat(writes.get(0).header("Idempotency-Key")).contains(account.id().value().toString(), product.value().toString());
        assertThat(writes.get(1).header("Idempotency-Key")).contains(account.id().value().toString(), draft.id().value().toString(), "v1");
        assertThat(http.calls()).allSatisfy(c -> assertThat(c.header("Stripe-Account")).isNull());
        assertThat(jdbc.queryForList("select status from provider_operations", String.class))
            .containsExactlyInAnyOrder("ACKNOWLEDGED", "ACKNOWLEDGED");
    }

    @Test void wrongKeyAccountRejectsBeforeAnyWrite() {
        var draft = catalogPrice("wrong-account", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_wrong\",\"object\":\"account\"}");
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).singleElement().satisfies(c -> assertThat(c.method()).isEqualTo("GET"));
        assertThat(count("provider_operations")).isZero();
        assertThat(count("provider_price_mappings")).isZero();
        assertThat(loadPrices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
    }

    @ParameterizedTest @ValueSource(strings = {"product", "price"})
    void directAdapterCallsVerifyCurrentAccountBeforeReceiptOrPost(String operation) {
        var draft = catalogPrice("direct-wrong-account", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        var stripeAccount = PaymentAccount.create(account.id(), "test-account", PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, "acct_expected", "stripe-sandbox-default");
        var storedProduct = loadProducts.findById(product).orElseThrow();
        http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_wrong\",\"object\":\"account\"}");
        var failure = catchThrowable(() -> {
            if (operation.equals("product")) stripeCatalog.ensureProduct(stripeAccount, storedProduct);
            else stripeCatalog.createPrice(stripeAccount, storedProduct, draft);
        });
        assertThat(http.calls()).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("GET"); assertThat(c.path()).isEqualTo("/v1/account");
        });
        assertThat(failure).isInstanceOf(dev.genericpayments.platform.shared.domain.DomainValidationException.class);
        assertThat(count("provider_operations")).isZero();
        assertThat(count("provider_product_mappings")).isZero();
        assertThat(count("provider_price_mappings")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"product", "price"})
    void directStripeAdapterRejectsFakeBindingWithoutReservingReceipt(String operation) {
        jdbc.update("update payment_accounts set provider='FAKE',external_account_id='fake-account',credentials_ref='config-ref' where id=?", account.id().value());
        var draft = catalogPrice("direct-fake", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        var storedProduct = loadProducts.findById(product).orElseThrow();
        assertThatThrownBy(() -> {
            if (operation.equals("product")) stripeCatalog.ensureProduct(account, storedProduct);
            else stripeCatalog.createPrice(account, storedProduct, draft);
        }).isInstanceOf(dev.genericpayments.platform.shared.domain.DomainValidationException.class);
        assertThat(http.calls()).isEmpty();
        assertThat(count("provider_operations")).isZero();
        assertThat(count("provider_product_mappings")).isZero();
        assertThat(count("provider_price_mappings")).isZero();
    }

    @Test void exclusiveTaxRejectsBeforeAnyProviderPost() {
        var draft = catalogPrice("exclusive", 1, PriceType.ONE_TIME, TaxMode.EXCLUSIVE, false);
        http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_expected\",\"object\":\"account\"}");
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).noneMatch(c -> c.method().equals("POST"));
        assertThat(count("provider_operations")).isZero();
    }

    @Test void acknowledgedReceiptsRepairFailedLocalCommitWithoutCreatingProviderObjectsAgain() {
        var draft = catalogPrice("retry-local", 1, PriceType.ONE_TIME, TaxMode.INCLUSIVE, false);
        rejectPublication();
        enqueuePublication();
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOf(RuntimeException.class);
        assertThat(count("provider_product_mappings")).isZero();
        assertThat(count("provider_price_mappings")).isZero();
        assertThat(loadPrices.findById(draft.id()).orElseThrow().status()).isEqualTo(PriceStatus.DRAFT);
        var receipts = jdbc.queryForList("select id,operation_key,provider_reference,version from provider_operations order by kind");
        jdbc.execute("drop trigger reject_catalog_publish on prices");
        identity(); identity();
        assertThat(publication.publish(draft.id()).version()).isEqualTo(1);
        assertThat(jdbc.queryForList("select id,operation_key,provider_reference,version from provider_operations order by kind")).isEqualTo(receipts);
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(2);
        assertThat(mappings.findPrice(account.id(), draft.id()).orElseThrow().value()).isEqualTo("price_fixture");
    }

    @ParameterizedTest @ValueSource(strings = {"amount_minor=5000", "price_version=2"})
    void changedDraftCannotReuseAcknowledgedReceipt(String mutation) {
        var draft = catalogPrice("changed-draft", 1, PriceType.ONE_TIME, TaxMode.INCLUSIVE, false);
        rejectPublication(); enqueuePublication();
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOf(RuntimeException.class);
        jdbc.execute("drop trigger reject_catalog_publish on prices");
        jdbc.update("update prices set " + mutation + ",version=version+1 where id=?", draft.id().value());
        identity(); identity();
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOfSatisfying(PaymentApplicationException.class,
            e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(2);
        assertThat(count("provider_price_mappings")).isZero();
    }

    @Test void publishesRecurringTermsWithoutEnablingStripeSubscriptionCommands() {
        var draft = Price.draft(new PriceId(UUID.randomUUID()), site, product, PriceKey.of("annual"), 1,
            PriceType.SUBSCRIPTION, Money.of(12345, "EUR"), BillingInterval.YEAR, 14,
            TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL, TaxMode.INCLUSIVE, null, INITIAL_TIME);
        prices.save(draft); enqueuePublication();
        var result = publication.publish(draft.id());
        assertThat(result.billingInterval()).isEqualTo(BillingInterval.YEAR);
        assertThat(result.trialPaymentMethodPolicy()).isEqualTo(TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);
        assertThat(http.calls().stream().filter(c -> c.path().equals("/v1/prices"))).singleElement().satisfies(c ->
            assertThat(c.form()).containsEntry("unit_amount", "12345").containsEntry("tax_behavior", "inclusive")
                .containsEntry("recurring[interval]", "year").containsEntry("recurring[interval_count]", "1")
                .containsEntry("recurring[trial_period_days]", "14"));
        var stripeAccount = PaymentAccount.create(account.id(), "test-account", PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, "acct_expected", "stripe-sandbox-default");
        assertThatThrownBy(() -> subscriptions.forAccount(stripeAccount)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void accountSpecificMappingCannotBeTakenFromAnotherAccount() {
        var other = new PaymentAccountId(UUID.randomUUID());
        jdbc.update("insert into payment_accounts values (?, 'other-account', 'FAKE', 'SANDBOX', 'ACTIVE', 'fake-other', 'other-ref', ?, 0)",
            other.value(), java.sql.Timestamp.from(INITIAL_TIME));
        var draft = catalogPrice("account-bound", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        mappings.save(new ProviderCatalogMapping(other, product, new ProviderProductRef("fake_other_product"), draft.id(),
            new ProviderPriceRef("fake_other_price"), INITIAL_TIME));
        enqueuePublication();
        publication.publish(draft.id());
        assertThat(mappings.findPrice(other, draft.id()).orElseThrow().value()).isEqualTo("fake_other_price");
        assertThat(mappings.findPrice(account.id(), draft.id()).orElseThrow().value()).isEqualTo("price_fixture");
        assertThat(http.calls().stream().filter(c -> c.path().equals("/v1/prices"))).singleElement()
            .satisfies(c -> assertThat(c.form()).containsEntry("product", "prod_fixture"));
    }

    @Test void uncertainPostReusesTheOriginalKeyAndDoesNotLeakRawErrors() {
        var draft = catalogPrice("unknown-result", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        identity();
        http.enqueue("POST", "/v1/products", 500, "{\"error\":{\"type\":\"api_error\",\"message\":\"private-response-marker\"}}");
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOfSatisfying(PaymentApplicationException.class,
            e -> { assertThat(e.code()).isEqualTo("PROVIDER_RESULT_UNKNOWN"); assertThat(e.getCause()).isNull();
                assertThat(e.getMessage()).doesNotContain("private-response-marker"); });
        assertThat(jdbc.queryForObject("select status from provider_operations", String.class)).isEqualTo("UNKNOWN");
        String key = http.calls().get(1).header("Idempotency-Key");
        enqueuePublication();
        publication.publish(draft.id());
        assertThat(http.calls().stream().filter(c -> c.path().equals("/v1/products")))
            .hasSize(2).allSatisfy(c -> assertThat(c.header("Idempotency-Key")).isEqualTo(key));
        assertThat(count("provider_operations")).isEqualTo(2);
    }

    @Test void expiredUncertainOperationRequiresRecoveryWithoutAnotherPost() {
        var draft = catalogPrice("expired-result", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        identity();
        http.enqueue("POST", "/v1/products", 500, "{\"error\":{\"type\":\"api_error\",\"message\":\"uncertain\"}}");
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOf(PaymentApplicationException.class);
        jdbc.update("update provider_operations set first_dispatched_at=now()-interval '24 hours',retry_before=now()-interval '1 hour'");
        String key = jdbc.queryForObject("select operation_key from provider_operations", String.class);
        identity();
        assertThatThrownBy(() -> publication.publish(draft.id())).isInstanceOfSatisfying(PaymentApplicationException.class,
            e -> assertThat(e.code()).isEqualTo("PROVIDER_RESULT_UNKNOWN"));
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
        assertThat(jdbc.queryForObject("select operation_key from provider_operations", String.class)).isEqualTo(key);
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test void laterPriceUsesExistingProductAndLeavesPublishedPriceAndMappingUnchanged() {
        var first = catalogPrice("first-published", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        enqueuePublication();
        var published = publication.publish(first.id());
        var oldMapping = mappings.findPrice(account.id(), first.id());
        var later = catalogPrice("later-price", 2, PriceType.ONE_TIME, TaxMode.INCLUSIVE, false);
        identity();
        http.enqueue("POST", "/v1/prices", 200, "{\"id\":\"price_later\",\"object\":\"price\",\"livemode\":false}");
        assertThat(publication.publish(later.id()).version()).isEqualTo(2);
        assertThat(loadPrices.findById(first.id()).orElseThrow()).isEqualTo(published);
        assertThat(mappings.findPrice(account.id(), first.id())).isEqualTo(oldMapping);
        assertThat(http.calls().stream().filter(c -> c.path().equals("/v1/products"))).hasSize(1);
        assertThat(http.calls().stream().filter(c -> c.path().equals("/v1/prices"))).hasSize(2)
            .allSatisfy(c -> assertThat(c.method()).isEqualTo("POST"));
    }

    @Test void subscriptionWithoutTrialOmitsTrialParameter() {
        var draft = catalogPrice("no-trial", 1, PriceType.SUBSCRIPTION, TaxMode.INCLUSIVE, false);
        enqueuePublication();
        publication.publish(draft.id());
        assertThat(http.calls().stream().filter(c -> c.path().equals("/v1/prices"))).singleElement().satisfies(c ->
            assertThat(c.form()).containsEntry("recurring[interval]", "month").doesNotContainKey("recurring[trial_period_days]"));
    }

    @Test void enabledStripeRegistryStillPublishesFakeAccountWithoutHttp() {
        jdbc.update("update payment_accounts set provider='FAKE',external_account_id='fake-account',credentials_ref='config-ref' where id=?", account.id().value());
        var draft = catalogPrice("mixed-fake", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        assertThat(publication.publish(draft.id()).status()).isEqualTo(PriceStatus.PUBLISHED);
        assertThat(mappings.findPrice(account.id(), draft.id()).orElseThrow().value()).startsWith("fake_price_");
        assertThat(http.calls()).isEmpty();
        assertThat(count("provider_operations")).isZero();
    }

    void identity() { http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_expected\",\"object\":\"account\"}"); }

    void rejectPublication() {
        jdbc.execute("create function reject_catalog_publish() returns trigger language plpgsql as $$ begin if new.status='PUBLISHED' then raise exception 'injected publication failure' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger reject_catalog_publish before update on prices for each row execute function reject_catalog_publish()");
    }

    void enqueuePublication() {
        http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_expected\",\"object\":\"account\"}");
        http.enqueue("POST", "/v1/products", 200, "{\"id\":\"prod_fixture\",\"object\":\"product\",\"livemode\":false}");
        identity();
        http.enqueue("POST", "/v1/prices", 200, "{\"id\":\"price_fixture\",\"object\":\"price\",\"livemode\":false}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean @org.springframework.context.annotation.Primary
        FixtureFactory fixtureFactory() { return new FixtureFactory(); }
    }
    static class FixtureFactory extends StripeClientFactory {
        volatile StripeHttpFixture http;
        @Override protected StripeClient.StripeClientBuilder builder(StripeCredentials credentials) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return super.builder(credentials).setApiBase(http.baseUri().toString());
        }
    }
}
