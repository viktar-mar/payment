package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;

import com.stripe.StripeClient;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.transaction.support.*;
import org.springframework.transaction.PlatformTransactionManager;

@ActiveProfiles("test")
@TestPropertySource(properties = {
    "payment.stripe.sandbox.enabled=true",
    "payment.stripe.sandbox.secret-key=sk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "payment.stripe.sandbox.publishable-key=pk_test_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
    "payment.stripe.sandbox.webhook-secret=whsec_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"})
@Import(StripePaymentProviderAdapterTest.FixtureConfiguration.class)
public class StripePaymentProviderAdapterTest extends CheckoutTestFixture {
    @Autowired PaymentProviderRegistry registry;
    @Autowired FixtureFactory factory;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StripeCatalogProviderAdapter catalog;
    StripeHttpFixture http;
    PaymentAccount stripeAccount;

    @BeforeEach void setupStripe() throws IOException {
        http = new StripeHttpFixture(); factory.http = http;
        stripeAccount = PaymentAccount.create(account.id(), "test-account", PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, "acct_expected", "stripe-sandbox-default");
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
    }
    @AfterEach void closeHttp() { http.close(); }

    @Test void repeatedPreparationUsesOneIntentWithOriginalMoneyAccountAndKey() {
        var p = save.save(payment());
        identity(); intent();
        assertThat(stripe().preparePayment(request(p))).isEqualTo(new ProviderResult.Accepted("pi_fixture", false));
        identity();
        assertThat(stripe().preparePayment(request(p))).isEqualTo(new ProviderResult.Accepted("pi_fixture", false));
        var writes = http.calls().stream().filter(c -> c.method().equals("POST")).toList();
        assertThat(writes).singleElement().satisfies(c -> {
            assertThat(c.path()).isEqualTo("/v1/payment_intents");
            assertThat(c.header("Idempotency-Key")).isEqualTo("prepare_" + p.id().value());
            assertThat(c.form()).containsEntry("amount", "2000").containsEntry("currency", "eur")
                .containsEntry("allowed_payment_method_types[0]", "card").containsEntry("capture_method", "automatic")
                .containsEntry("metadata[payment_id]", p.id().value().toString())
                .containsEntry("metadata[site_id]", site.value().toString()).containsEntry("metadata[source]", "DYNAMIC");
            assertThat(c.form()).hasSize(7).doesNotContainKeys("setup_future_usage", "receipt_email", "customer", "automatic_payment_methods[enabled]", "payment_method_types[0]");
        });
        assertThat(http.calls()).hasSize(3).allSatisfy(c -> assertThat(c.header("Stripe-Account")).isNull());
        assertThat(count("provider_operations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from provider_operations", String.class)).isEqualTo("ACKNOWLEDGED");
    }

    @Test void currentWrongAccountKeyNeverReservesOrWrites() {
        var p = save.save(payment());
        http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_wrong\",\"object\":\"account\"}");
        assertThatThrownBy(() -> stripe().preparePayment(request(p))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).singleElement().satisfies(c -> assertThat(c.method()).isEqualTo("GET"));
        assertThat(count("provider_operations")).isZero();
    }

    @Test void directFakeBindingCannotReachReceiptDespiteNoopVerifier() {
        var adapter = stripe();
        jdbc.update("update payment_accounts set provider='FAKE',external_account_id='fake-account',credentials_ref='config-ref' where id=?", account.id().value());
        var p = save.save(payment());
        assertThatThrownBy(() -> adapter.preparePayment(new ProviderPaymentRequest(account, p, ProviderOperationKeys.prepare(p.id()))))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("provider_operations")).isZero(); assertThat(http.calls()).isEmpty();
    }

    @Test void directInvalidAliasCannotReachReceipt() {
        var p = save.save(payment());
        var invalid = PaymentAccount.create(account.id(), "test-account", PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, "acct_expected", "unavailable-alias");
        assertThatThrownBy(() -> stripe().preparePayment(new ProviderPaymentRequest(invalid, p, ProviderOperationKeys.prepare(p.id()))))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("provider_operations")).isZero(); assertThat(http.calls()).isEmpty();
    }

    @Test void ambientTransactionIsRejectedBeforeIdentityOrFinancialWrite() {
        var p = save.save(payment());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> stripe().preparePayment(request(p))))
            .isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).isEmpty(); assertThat(count("provider_operations")).isZero();
    }

    @Test void uncommittedResourceCannotReserveOrDispatch() {
        identity();
        assertThatThrownBy(() -> stripe().preparePayment(request(payment()))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).noneMatch(c -> c.method().equals("POST"));
        assertThat(count("provider_operations")).isZero();
    }

    @ParameterizedTest @ValueSource(longs = {1, 49, 100000000})
    void euroAmountOutsideStripeBoundsCannotWrite(long amount) {
        var p = save.save(dynamic(amount, "EUR", TaxMode.NONE)); identity();
        assertThatThrownBy(() -> stripe().preparePayment(request(p))).isInstanceOf(RuntimeException.class);
        assertThat(count("provider_operations")).isZero();
        assertThat(http.calls()).noneMatch(c -> c.method().equals("POST"));
    }

    @Test void unsupportedCurrencyAndExclusiveTaxCannotWrite() {
        for (var p : new Payment[]{dynamic(2000, "USD", TaxMode.NONE), dynamic(2000, "EUR", TaxMode.EXCLUSIVE)}) {
            save.save(p); identity();
            assertThatThrownBy(() -> stripe().preparePayment(request(p))).isInstanceOf(RuntimeException.class);
        }
        assertThat(count("provider_operations")).isZero();
        assertThat(http.calls()).noneMatch(c -> c.method().equals("POST"));
    }

    @Test void allowlistWithoutCardRejectsBeforeReceiptOrWrite() {
        jdbc.update("update sites set payment_method_policy='ALLOWLIST' where id=?", site.value());
        jdbc.update("insert into site_allowed_payment_methods values (?, 'PAYPAL')", site.value());
        var p = save.save(payment()); identity();
        assertThatThrownBy(() -> stripe().preparePayment(request(p))).isInstanceOf(RuntimeException.class);
        assertThat(count("provider_operations")).isZero();
        assertThat(http.calls()).noneMatch(c -> c.method().equals("POST"));
    }

    @ParameterizedTest @ValueSource(strings = {"disconnect", "timeout", "server-error"})
    void ambiguousCreationReplaysOnlyTheOriginalKey(String failure) {
        var p = save.save(payment()); identity();
        if (failure.equals("timeout")) factory.readTimeout = 100;
        switch (failure) {
            case "disconnect" -> http.disconnectNext("POST", "/v1/payment_intents");
            case "timeout" -> http.enqueue("POST", "/v1/payment_intents", 200,
                "{\"id\":\"pi_fixture\",\"object\":\"payment_intent\",\"livemode\":false}", Map.of(), 300);
            default -> http.enqueue("POST", "/v1/payment_intents", 500,
                "{\"error\":{\"type\":\"api_error\",\"message\":\"private-response-marker\"}}");
        }
        assertThat(stripe().preparePayment(request(p))).isEqualTo(new ProviderResult.Unknown(null));
        assertThat(jdbc.queryForObject("select status from provider_operations", String.class)).isEqualTo("UNKNOWN");
        factory.readTimeout = 5000;
        identity(); intent();
        assertThat(stripe().preparePayment(request(p))).isEqualTo(new ProviderResult.Accepted("pi_fixture", false));
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(2)
            .allSatisfy(c -> assertThat(c.header("Idempotency-Key")).isEqualTo("prepare_" + p.id().value()));
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test void changedOriginalMoneyCannotReuseAcknowledgedReceipt() {
        var p = save.save(payment()); identity(); intent(); stripe().preparePayment(request(p));
        jdbc.update("update payments set amount_minor=2001,version=version+1 where id=?", p.id().value());
        identity();
        assertThatThrownBy(() -> stripe().preparePayment(request(load.findById(site, p.id()).orElseThrow())))
            .isInstanceOf(RuntimeException.class);
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test void expiredUnknownReceiptCannotMintAnotherIntent() {
        var p = save.save(payment()); identity();
        http.disconnectNext("POST", "/v1/payment_intents"); stripe().preparePayment(request(p));
        jdbc.update("update provider_operations set first_dispatched_at=now()-interval '24 hours',retry_before=now()-interval '1 hour'");
        identity();
        assertThat(stripe().preparePayment(request(p))).isEqualTo(new ProviderResult.Unknown(null));
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test void persistedCatalogFingerprintPreservesNullEmptyAndSupplementaryUnicode() throws Exception {
        var method = StripeCatalogProviderAdapter.class.getDeclaredMethod("fingerprint", PaymentAccount.class, String[].class);
        method.setAccessible(true);
        var target = org.springframework.test.util.AopTestUtils.getTargetObject(catalog);
        assertThat(method.invoke(target, stripeAccount, new String[]{"ab", null, "", "😀"}))
            .isEqualTo("8a8b47301c753730d3d32785887b2b32174bbe59171241d2425d915f4bcc77e0");
        assertThat(method.invoke(target, stripeAccount, new String[]{"", null, "😀"}))
            .isEqualTo("538bf22ffcaad96531f094d766fccfe8b995dc680d515024cbbbd9598fd79426");
    }

    @Test void acknowledgedOriginalAccountRemainsReachableAfterSiteReassignmentAndPolicyChange() {
        var p = save.save(payment()); identity(); intent(); stripe().preparePayment(request(p));
        var other = java.util.UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?, 'other-account', 'FAKE', 'SANDBOX', 'ACTIVE', 'other', 'other-ref', ?, 0)", other, java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("update sites set payment_account_id=?,payment_method_policy='ALLOWLIST' where id=?", other, site.value());
        jdbc.update("insert into site_allowed_payment_methods values (?, 'PAYPAL')", site.value());
        identity();
        assertThat(stripe().preparePayment(request(p))).isEqualTo(new ProviderResult.Accepted("pi_fixture", false));
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
        assertThat(jdbc.queryForObject("select payment_account_id from provider_operations", java.util.UUID.class)).isEqualTo(account.id().value());
    }

    @Test void currentPolicyGatesUnknownReplayBeforeAnotherPost() {
        var p = save.save(payment()); identity(); http.disconnectNext("POST", "/v1/payment_intents");
        stripe().preparePayment(request(p));
        jdbc.update("update sites set payment_method_policy='ALLOWLIST' where id=?", site.value());
        jdbc.update("insert into site_allowed_payment_methods values (?, 'PAYPAL')", site.value());
        identity();
        assertThatThrownBy(() -> stripe().preparePayment(request(p))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
        assertThat(jdbc.queryForObject("select status from provider_operations", String.class)).isEqualTo("UNKNOWN");
    }

    @Test void requestUsesImmutablePaymentMoneyWithoutFutureCharges() {
        var p = payment();
        var params = StripePaymentRequests.prepare(p);
        assertThat(params.getAmount()).isEqualTo(2000L);
        assertThat(params.getCurrency()).isEqualTo("eur");
        assertThat(params.getSetupFutureUsage()).isNull();
        assertThat(params.getAllowedPaymentMethodTypes()).containsExactly(com.stripe.param.PaymentIntentCreateParams.AllowedPaymentMethodType.CARD);
        assertThat(params.getMetadata()).containsOnlyKeys("payment_id", "site_id", "source");
    }

    @ParameterizedTest @ValueSource(longs = {50, 99999999})
    void euroBoundaryAmountsArePreserved(long amount) {
        var p = save.save(dynamic(amount, "EUR", TaxMode.INCLUSIVE)); identity(); intent();
        assertThat(stripe().preparePayment(request(p))).isEqualTo(new ProviderResult.Accepted("pi_fixture", false));
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).singleElement()
            .satisfies(c -> assertThat(c.form()).containsEntry("amount", Long.toString(amount)).containsEntry("currency", "eur"));
    }

    @Test void subscriptionSourceRemainsUnavailableBeforeProviderWrite() {
        var context = new SubscriptionBillingContext(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
            SubscriptionBillingContext.Kind.INITIAL, product, price.id(), 1, null, null, INITIAL_TIME,
            INITIAL_TIME.plusSeconds(3600), "in_fixture");
        var p = Payment.subscription(payment().id(), site, java.util.UUID.randomUUID(), account.id(), context,
            Money.of(2000, "EUR"), TaxMode.NONE, Map.of(), INITIAL_TIME);
        assertThatThrownBy(() -> StripePaymentRequests.prepare(p)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThat(http.calls()).isEmpty(); assertThat(count("provider_operations")).isZero();
    }

    @Test void changedRequestBeforeFirstReservationCannotChangeOriginalMoney() {
        var original = save.save(payment());
        var altered = Payment.dynamic(original.id(), site, account.id(), original.externalOrderId(), Money.of(2001, "EUR"),
            original.taxMode(), original.technicalMetadata(), INITIAL_TIME);
        identity();
        assertThatThrownBy(() -> stripe().preparePayment(request(altered)))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,
                e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThat(count("provider_operations")).isZero();
        assertThat(http.calls()).singleElement().satisfies(c -> assertThat(c.method()).isEqualTo("GET"));
    }

    @Test void changedOriginalAccountCannotReuseReceipt() {
        var p = save.save(payment()); identity(); intent(); stripe().preparePayment(request(p));
        jdbc.update("update payment_accounts set external_account_id='acct_replaced' where id=?", account.id().value());
        var replaced = PaymentAccount.create(account.id(), "test-account", PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, "acct_replaced", "stripe-sandbox-default");
        http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_replaced\",\"object\":\"account\"}");
        assertThatThrownBy(() -> registry.select(replaced).preparePayment(new ProviderPaymentRequest(replaced, p, ProviderOperationKeys.prepare(p.id()))))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,
                e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
        assertThat(jdbc.queryForObject("select external_account_id from provider_operations", String.class)).isEqualTo("acct_expected");
    }

    @Test void capabilitiesRequireClientConfirmationAndUnimplementedOperationsFailClosed() {
        var capabilities = stripe().capabilities(stripeAccount);
        assertThat(capabilities.confirmationMode()).isEqualTo(PaymentProviderCapabilities.ConfirmationMode.CLIENT_PROVIDER);
        assertThat(capabilities.methods()).containsExactly(dev.genericpayments.platform.sites.domain.PaymentMethod.CARD);
        assertThat(capabilities.taxModes()).containsExactlyInAnyOrder(TaxMode.NONE, TaxMode.INCLUSIVE);
        assertThatThrownBy(() -> capabilities.require(dev.genericpayments.platform.sites.domain.PaymentMethod.CARD)).isInstanceOf(IllegalStateException.class);
        // Refusal must not depend on or dispatch a server request.
        assertThatThrownBy(() -> stripe().confirmPayment(null)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThatThrownBy(() -> stripe().requestRefund(null)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThatThrownBy(() -> stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount, payment().id(), "pi_fixture")))
            .isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThatThrownBy(() -> stripe().retrieveRefund(null)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThat(http.calls()).isEmpty(); assertThat(count("provider_operations")).isZero();
    }

    @Test void errorMapperRedactsExceptionBodyAndNestedCause() {
        var result = StripePaymentErrorMapper.map(new IllegalStateException("private-response-marker", new IllegalStateException("private-secret-marker")));
        assertThat(result).isEqualTo(new ProviderResult.Unknown(null));
        assertThat(result.toString()).doesNotContain("private-response-marker", "private-secret-marker");
    }

    @Test void previouslyBoundReferenceWithoutPreparationReceiptCannotCreateAnotherIntent() {
        var p = save.save(payment().withProviderPaymentReference("pi_original", INITIAL_TIME)); identity(); intent();
        assertThatThrownBy(() -> stripe().preparePayment(request(p)))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,
                e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThat(count("provider_operations")).isZero();
        assertThat(http.calls()).singleElement().satisfies(c -> assertThat(c.method()).isEqualTo("GET"));
    }

    @Test void acknowledgedReceiptCannotOverrideDifferentStoredReference() {
        var p = save.save(payment()); identity(); intent(); stripe().preparePayment(request(p));
        save.save(p.withProviderPaymentReference("pi_other", INITIAL_TIME)); identity();
        assertThatThrownBy(() -> stripe().preparePayment(request(p)))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,
                e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
    }

    @Test void acknowledgedReplayStillVerifiesCurrentServerKey() {
        var p = save.save(payment()); identity(); intent(); stripe().preparePayment(request(p));
        http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_wrong\",\"object\":\"account\"}");
        assertThatThrownBy(() -> stripe().preparePayment(request(p))).isInstanceOf(dev.genericpayments.platform.shared.domain.DomainValidationException.class);
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"success", "ambiguous"})
    void staleNetworkCompletionCannotFinishNewerClaim(String result) throws Exception {
        var p = save.save(payment()); identity();
        http.enqueue("POST", "/v1/payment_intents", result.equals("success") ? 200 : 500,
            result.equals("success") ? "{\"id\":\"pi_fixture\",\"object\":\"payment_intent\",\"livemode\":false}"
                : "{\"error\":{\"type\":\"api_error\",\"message\":\"private-response-marker\"}}", Map.of(), 1000);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var oldCall = executor.submit(() -> catchThrowable(() -> stripe().preparePayment(request(p))));
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (http.calls().stream().noneMatch(c -> c.method().equals("POST")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
            jdbc.update("update provider_operations set lease_expires_at=clock_timestamp()-interval '1 microsecond'");
            var operations = operationStore;
            var receipt = operations.find(account.id(), "prepare_" + p.id().value()).orElseThrow();
            var newer = operations.claim(receipt.id(), "new-owner", java.time.Instant.now(), java.time.Duration.ofSeconds(60)).orElseThrow();
            assertThat(oldCall.get(10, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
            assertThat(operations.find(account.id(), receipt.operationKey())).contains(newer);
        }
    }

    @Autowired dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore operationStore;

    @Test void liveProviderClaimPreventsASecondFinancialPost() {
        var p = save.save(payment()); identity(); http.disconnectNext("POST", "/v1/payment_intents");
        stripe().preparePayment(request(p));
        var receipt = operationStore.find(account.id(), "prepare_" + p.id().value()).orElseThrow();
        var owner = operationStore.claim(receipt.id(), "active-owner", java.time.Instant.now(), java.time.Duration.ofSeconds(60)).orElseThrow();
        identity();
        assertThat(stripe().preparePayment(request(p))).isEqualTo(new ProviderResult.Unknown(null));
        assertThat(operationStore.find(account.id(), receipt.operationKey())).contains(owner);
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {
        "{\"id\":\"pi_live\",\"object\":\"payment_intent\",\"livemode\":true}",
        "{\"id\":\"private-capability-marker\",\"object\":\"payment_intent\",\"livemode\":false}",
        "private-response-marker"})
    void unusableSdkResponseIsSafeUnknownWithoutBinding(String json) {
        var p = save.save(payment()); identity(); http.enqueue("POST", "/v1/payment_intents", 200, json);
        assertThat(stripe().preparePayment(request(p))).isEqualTo(new ProviderResult.Unknown(null));
        assertThat(jdbc.queryForObject("select status from provider_operations", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select provider_reference from provider_operations", String.class)).isNull();
    }

    PaymentProviderPort stripe() { return registry.select(stripeAccount); }
    @Test void retrieveVerifiesCapturedChargeWithoutConsumingReceiptOrFinancialFacts() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        identity();snapshot("succeeded","ch_current",null);chargeSnapshot("ch_current",true,"pi_fixture");
        var result=stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture"));
        assertThat(result.status()).isEqualTo(ProviderState.Status.SUCCEEDED);
        assertThat(result.evidence().money()).isEqualTo(Money.of(2000,"EUR"));
        assertThat(result.evidence().chargeReference()).isEqualTo("ch_current");
        assertThat(http.calls()).extracting(c -> c.path()).containsExactly("/v1/account","/v1/payment_intents/pi_fixture","/v1/charges/ch_current");
        assertThat(count("provider_charge_receipts")).isZero();assertThat(count("payment_events")).isZero();
        assertThat(load.findById(site,p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.CREATED);
    }
    @Test void retrieveOldFailedChargeAgainDoesNotProduceAnotherReceiptOrTerminalFailure() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        for(int n=0;n<2;n++) {
            identity();snapshot("requires_payment_method","ch_declined","ch_declined");chargeSnapshot("ch_declined",false,"pi_fixture");
            var result=stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture"));
            assertThat(result.status()).isEqualTo(ProviderState.Status.REQUIRES_PAYMENT_METHOD);
            assertThat(result.evidence().failedChargeReference()).isEqualTo("ch_declined");
        }
        assertThat(count("provider_charge_receipts")).isZero();assertThat(count("payment_events")).isZero();
    }
    @Test void retrieveWithoutNewChargeDoesNotCreateReceipt() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        identity();snapshot("requires_payment_method",null,null);
        assertThat(stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture")).status())
            .isEqualTo(ProviderState.Status.REQUIRES_PAYMENT_METHOD);
        assertThat(count("provider_charge_receipts")).isZero();assertThat(http.calls()).hasSize(2);
    }
    @Test void retrieveWrongCurrentServerAccountStopsBeforeIntentAndReceipt() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_wrong\",\"object\":\"account\"}");
        assertThatThrownBy(() -> stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture"))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).singleElement().satisfies(c -> assertThat(c.path()).isEqualTo("/v1/account"));
        assertThat(count("provider_charge_receipts")).isZero();assertThat(count("payment_events")).isZero();
    }
    @Test void directRetrieveFakeOrInvalidAliasCannotReachNoopVerifierOrReceipts() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        var adapter=stripe();
        var invalid=PaymentAccount.create(account.id(),"test-account",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_expected","unavailable-alias");
        for(var binding:java.util.List.of(account,invalid))
            assertThatThrownBy(() -> adapter.retrievePayment(ProviderPaymentLookup.forPayment(binding,p.id(),"pi_fixture"))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).isEmpty();assertThat(count("provider_charge_receipts")).isZero();
    }
    @Test void foreignFailedChargePreventsAnyReceiptEvenWhenSuccessChargeIsValid() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        identity();snapshot("succeeded","ch_success","ch_old");chargeSnapshot("ch_success",true,"pi_fixture");chargeSnapshot("ch_old",false,"pi_foreign");
        assertThatThrownBy(() -> stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture"))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).extracting(c -> c.path()).containsExactly("/v1/account","/v1/payment_intents/pi_fixture","/v1/charges/ch_success","/v1/charges/ch_old");
        assertThat(count("provider_charge_receipts")).isZero();
    }
    @Test void distinctPriorFailedChargeIsRetrievedAndVerifiedUnderOriginalAccount() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        identity();snapshot("succeeded","ch_success","ch_old");chargeSnapshot("ch_success",true,"pi_fixture");chargeSnapshot("ch_old",false,"pi_fixture");
        var result=stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture"));
        assertThat(result.status()).isEqualTo(ProviderState.Status.SUCCEEDED);
        assertThat(result.evidence().chargeReference()).isEqualTo("ch_success");
        assertThat(result.evidence().failedChargeReference()).isEqualTo("ch_old");
        assertThat(http.calls()).extracting(c -> c.path()).containsExactly("/v1/account","/v1/payment_intents/pi_fixture","/v1/charges/ch_success","/v1/charges/ch_old");
        assertThat(http.calls()).allSatisfy(c -> assertThat(c.header("Stripe-Account")).isNull());
        assertThat(count("provider_charge_receipts")).isZero();assertThat(count("payment_events")).isZero();
    }
    @Test void retrieveUsesOriginalPaymentEvenAfterSiteAccountChanges() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        var other=java.util.UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'another-account','FAKE','SANDBOX','ACTIVE','fake-other','other-ref',?,0)",other,java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("update sites set payment_account_id=? where id=?",other,site.value());
        identity();snapshot("succeeded","ch_current",null);chargeSnapshot("ch_current",true,"pi_fixture");
        assertThat(stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture")).evidence().accountId()).isEqualTo(account.id());
    }
    @Test void retrieveRequiresStoredBindingRatherThanCallerSuppliedIdentity() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        jdbc.update("update payment_accounts set external_account_id='acct_changed' where id=?",account.id().value());
        assertThatThrownBy(() -> stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture"))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).isEmpty();assertThat(count("provider_charge_receipts")).isZero();
    }
    @Test void retrieveUnderAmbientTransactionCannotContactStripe() {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture")))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls()).isEmpty();assertThat(count("provider_charge_receipts")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"server-error","malformed","null"})
    void retrieveSdkFailureDoesNotExposeResponseOrPersistFacts(String failure) {
        var p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));identity();
        http.enqueue("GET","/v1/payment_intents/pi_fixture",failure.equals("server-error")?500:200,
            failure.equals("server-error")?"{\"error\":{\"type\":\"api_error\",\"message\":\"private-snapshot-marker\"}}":failure.equals("null")?"null":"private-snapshot-marker");
        var error=catchThrowable(() -> stripe().retrievePayment(ProviderPaymentLookup.forPayment(stripeAccount,p.id(),"pi_fixture")));
        assertThat(error).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class)
            .hasNoCause().hasMessageNotContaining("private-snapshot-marker");
        assertThat(count("provider_charge_receipts")).isZero();assertThat(count("payment_events")).isZero();
    }
    void snapshot(String status,String latest,String failed) {
        http.enqueue("GET","/v1/payment_intents/pi_fixture",200,"""
            {"id":"pi_fixture","object":"payment_intent","livemode":false,"status":"%s",
             "amount":2000,"amount_received":%d,"currency":"eur","capture_method":"automatic",
             "payment_method_types":["card"],"latest_charge":%s,"last_payment_error":%s}
            """.formatted(status,status.equals("succeeded")?2000:0,latest==null?"null":"\""+latest+"\"",failed==null?"null":"{\"charge\":\""+failed+"\"}"));
    }
    void chargeSnapshot(String reference,boolean paid,String intent) {
        http.enqueue("GET","/v1/charges/"+reference,200,"""
            {"id":"%s","object":"charge","livemode":false,"payment_intent":"%s","amount":2000,
             "amount_captured":%d,"currency":"eur","paid":%s,"captured":%s,"status":"%s",
             "payment_method_details":{"type":"card"}}
            """.formatted(reference,intent,paid?2000:0,paid,paid,paid?"succeeded":"failed"));
    }
    @Override protected ProviderPaymentRequest request(Payment p) { return new ProviderPaymentRequest(stripeAccount, p, ProviderOperationKeys.prepare(p.id())); }
    Payment dynamic(long amount, String currency, TaxMode tax) {
        return Payment.dynamic(payment().id(), site, account.id(), "owned-order-" + java.util.UUID.randomUUID(), Money.of(amount, currency), tax, Map.of(), INITIAL_TIME);
    }
    void identity() { http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_expected\",\"object\":\"account\"}"); }
    void intent() { http.enqueue("POST", "/v1/payment_intents", 200,
        "{\"id\":\"pi_fixture\",\"object\":\"payment_intent\",\"livemode\":false,\"client_secret\":\"private-capability-marker\",\"status\":\"requires_payment_method\"}"); }

    @TestConfiguration(proxyBeanMethods = false)
    public static class FixtureConfiguration {
        @Bean @Primary public FixtureFactory paymentFixtureFactory() { return new FixtureFactory(); }
    }
    public static class FixtureFactory extends StripeClientFactory {
        public volatile StripeHttpFixture http;
        public volatile int readTimeout = 5000;
        @Override protected StripeClient.StripeClientBuilder builder(StripeCredentials credentials) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return super.builder(credentials).setApiBase(http.baseUri().toString()).setReadTimeout(readTimeout);
        }
    }
}
