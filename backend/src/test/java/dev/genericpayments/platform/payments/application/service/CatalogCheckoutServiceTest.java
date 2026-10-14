package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.payments.application.model.*;
import java.time.Clock;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeProviderStatePersistenceAdapter.Scenario;
import org.springframework.beans.factory.annotation.Autowired;

class CatalogCheckoutServiceTest extends CheckoutTestFixture {
    @Autowired CatalogCheckoutService service;
    @Autowired DigestPort digest;
    @Autowired IdempotencyService idempotency;
    @Autowired IdempotencyPort records;
    @Autowired CheckoutAuthorizationService authorization;
    @Autowired CatalogCheckoutTransaction checkoutPhases;
    @Autowired LoadPaymentAccountPort accounts;
    @Autowired Stage2TelemetryPort telemetry;
    @Autowired Clock clock;
    CreateCatalogCheckoutCommand command(String key, String idem) {
        return new CreateCatalogCheckoutCommand("test-site", key, URI.create("https://shop.test"), idem, "request-1");
    }
    @Test void snapshotsPublishedPriceAndReplaysOneTokenWithoutPreparingTwice() {
        var result = service.create(command("lifetime", "create-1"));
        assertThat(result.view().amountMinor()).isEqualTo(4999);
        assertThat(result.view().currency()).isEqualTo("EUR");
        assertThat(result.view().priceSnapshot().version()).isEqualTo(1);
        assertThat(Base64.getUrlDecoder().decode(result.checkoutToken())).hasSize(32);
        assertThat(result.view().expiresAt()).isEqualTo(INITIAL_TIME.plus(Duration.ofHours(24)));
        jdbc.update("update prices set status='ARCHIVED' where id=?", price.id().value());
        catalogPrice("lifetime", 2, PriceType.ONE_TIME, TaxMode.NONE, true);
        var replay = service.create(command("lifetime", "create-1"));
        assertThat(replay.checkoutToken()).isEqualTo(result.checkoutToken());
        assertThat(replay.view()).isEqualTo(result.view());
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("checkout_sessions")).isEqualTo(1);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select token_hash from checkout_sessions", String.class)).isEqualTo(digest.sha256Hex(result.checkoutToken()));
        assertThat(jdbc.queryForList("select * from checkout_sessions").toString()).doesNotContain(result.checkoutToken());
        assertThat(jdbc.queryForList("select * from idempotency_records").toString()).doesNotContain(result.checkoutToken());
        assertThat(result.toString()).doesNotContain(result.checkoutToken());
        assertThat(service.get(result.view().checkoutId(), URI.create("https://shop.test"), result.checkoutToken())).isEqualTo(result.view());
    }
    @Test void acceptsInclusiveButRejectsDraftSubscriptionAndUnsupportedTaxBeforePayment() {
        catalogPrice("inclusive", 1, PriceType.ONE_TIME, TaxMode.INCLUSIVE, true);
        assertThat(service.create(command("inclusive", "inclusive")).view().amountMinor()).isEqualTo(4999);
        catalogPrice("draft-only", 1, PriceType.ONE_TIME, TaxMode.NONE, false);
        catalogPrice("subscription", 1, PriceType.SUBSCRIPTION, TaxMode.NONE, true);
        catalogPrice("exclusive", 1, PriceType.ONE_TIME, TaxMode.EXCLUSIVE, true);
        catalogPrice("stripe-tax", 1, PriceType.ONE_TIME, TaxMode.STRIPE_TAX, true);
        for (var key : List.of("draft-only", "subscription", "exclusive", "stripe-tax")) {
            assertThatThrownBy(() -> service.create(command(key, key))).isInstanceOf(PaymentApplicationException.class);
        }
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
    }
    @Test void expiryReplayRetainsOriginalPaymentAndDoesNotCreateReplacement() {
        var result = service.create(command("lifetime", "expiry"));
        TestClock.set(INITIAL_TIME.plus(Duration.ofHours(24)));
        var failure = catchThrowable(() -> service.create(command("lifetime", "expiry")));
        assertThat(failure).isInstanceOf(PaymentApplicationException.class);
        assertThat(((PaymentApplicationException) failure).code()).isEqualTo("CHECKOUT_EXPIRED");
        assertThat(((PaymentApplicationException) failure).extensions()).containsEntry("payment_id", result.view().paymentId());
        assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select response_secret_state from idempotency_records", String.class)).isEqualTo("EXPIRED");
    }
    @Test void conflictingFingerprintCannotCreateAnotherPayment() {
        service.create(command("lifetime", "same"));
        catalogPrice("other-price", 1, PriceType.ONE_TIME, TaxMode.NONE, true);
        assertCode(() -> service.create(command("other-price", "same")), "IDEMPOTENCY_CONFLICT");
        assertThat(count("payments")).isEqualTo(1);
    }
    @Test void createAndConfirmStartRollbackAllTheirFacts() {
        rejectJournal(PaymentEventType.PAYMENT_CREATED);
        assertThatThrownBy(() -> service.create(command("lifetime", "rollback"))).isInstanceOf(RuntimeException.class);
        for (var table : List.of("payments", "payment_events", "checkout_sessions", "idempotency_records", "fake_provider_objects")) assertThat(count(table)).isZero();
        removeJournalFailure();
        var created = service.create(command("lifetime", "create"));
        rejectJournal(PaymentEventType.ATTEMPT_CREATED);
        assertThatThrownBy(() -> service.confirm(confirm(created.view().checkoutId(), created.checkoutToken(), "confirm"))).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_attempts")).isZero();
        assertThat(count("idempotency_records")).isEqualTo(1);
        assertThat(count("payment_events")).isEqualTo(2);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void sameConfirmKeyResumesExactlyOneAttemptAndDoesNotSynchronouslySucceed() {
        var created = service.create(command("lifetime", "create"));
        var c = confirm(created.view().checkoutId(), created.checkoutToken(), "confirm");
        assertThat(service.confirm(c).status()).isEqualTo("PROCESSING");
        assertThat(service.confirm(c).status()).isEqualTo("PROCESSING");
        assertThat(count("payment_attempts")).isEqualTo(1);
        assertThat(count("fake_provider_operations")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select response_secret_state from idempotency_records where scope like '%checkouts:confirm%'", String.class)).isEqualTo("NONE");
    }
    @Test void concurrentCreateKeysSerializeOnPostgres() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var calls = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) calls.add(executor.submit(() -> { gate.await(); return service.create(command("lifetime", "concurrent")).checkoutToken(); }));
            gate.countDown();
            var first = calls.getFirst().get(15, TimeUnit.SECONDS);
            for (var call : calls) assertThat(call.get(15, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("checkout_sessions")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void lateSessionAndCompletionFailuresRollbackInitialFacts() {
        rejectWrite("checkout_sessions", "insert", "true");
        assertThatThrownBy(() -> service.create(command("lifetime", "session-failure"))).isInstanceOf(RuntimeException.class);
        assertInitialEmpty(); clearWriteFailure();
        rejectWrite("idempotency_records", "update", "new.status='COMPLETED'");
        assertThatThrownBy(() -> service.create(command("lifetime", "completion-failure"))).isInstanceOf(RuntimeException.class);
        assertInitialEmpty(); clearWriteFailure();
        var c = service.create(command("lifetime", "created"));
        rejectWrite("idempotency_records", "update", "new.status='COMPLETED'");
        assertThatThrownBy(() -> service.confirm(confirm(c.view().checkoutId(), c.checkoutToken(), "completion-failure"))).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_attempts")).isZero();
        assertThat(count("payment_events")).isEqualTo(2);
        assertThat(count("idempotency_records")).isEqualTo(1);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void durableCreateAndConfirmResumeAfterAcknowledgementRollback() {
        rejectJournal(PaymentEventType.PAYMENT_METHOD_REQUIRED);
        assertThatThrownBy(() -> service.create(command("lifetime", "crash-create"))).isInstanceOf(RuntimeException.class);
        assertThat(count("checkout_sessions")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from payments", String.class)).isEqualTo("CREATED");
        assertThat(count("fake_provider_operations")).isEqualTo(1);
        removeJournalFailure();
        var c = service.create(command("lifetime", "crash-create"));
        assertThat(c.view().status()).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(count("fake_provider_operations")).isEqualTo(1);
        rejectJournal(PaymentEventType.PAYMENT_PROCESSING);
        var command = confirm(c.view().checkoutId(), c.checkoutToken(), "crash-confirm");
        assertThatThrownBy(() -> service.confirm(command)).isInstanceOf(RuntimeException.class);
        var attempt = jdbc.queryForObject("select id from payment_attempts", UUID.class);
        assertThat(jdbc.queryForObject("select status from payment_attempts", String.class)).isEqualTo("CREATED");
        removeJournalFailure();
        assertThat(service.confirm(command).status()).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select id from payment_attempts", UUID.class)).isEqualTo(attempt);
        assertThat(count("fake_provider_operations")).isEqualTo(2);
    }
    @Test void providerObservesCommittedInitialFactsWithNoActiveTransaction() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var provider = new DelegatingProvider(fake) {
            @Override public ProviderResult preparePayment(ProviderPaymentRequest request) {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(count("payments")).isEqualTo(1);
                assertThat(count("payment_events")).isEqualTo(1);
                assertThat(count("checkout_sessions")).isEqualTo(1);
                assertThat(jdbc.queryForObject("select status from idempotency_records", String.class)).isEqualTo("COMPLETED");
                calls.incrementAndGet(); return super.preparePayment(request);
            }
            @Override public ProviderResult confirmPayment(ProviderConfirmationRequest request) {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(count("payment_attempts")).isEqualTo(1);
                assertThat(count("payment_events")).isEqualTo(3);
                assertThat(jdbc.queryForObject("select count(*) from idempotency_records where status='COMPLETED'", Integer.class)).isEqualTo(2);
                calls.incrementAndGet(); return super.confirmPayment(request);
            }
        };
        var registry = new PaymentProviderRegistry(Map.of(account.provider(), provider));
        var checkout = new CatalogCheckoutService(authorization, checkoutPhases, load, attempts, accounts, registry,
            new PaymentPreparationCoordinator(creation, registry, telemetry, () -> 0),
            new PaymentConfirmationCoordinator(attemptPhase, registry, telemetry, () -> 0), digest, telemetry, clock);
        var c = checkout.create(command("lifetime", "probe"));
        checkout.confirm(confirm(c.view().checkoutId(), c.checkoutToken(), "probe"));
        assertThat(calls).hasValue(2);
    }
    @Test void unconfiguredTelemetryFailsSafelyBeforeAnyBusinessTransaction() {
        var denied = new dev.genericpayments.platform.shared.adapter.out.observability.MicrometerStage2TelemetryAdapter(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), Set.of());
        var registry = new PaymentProviderRegistry(Map.of(account.provider(), fake));
        var checkout = new CatalogCheckoutService(authorization, checkoutPhases, load, attempts, accounts, registry,
            new PaymentPreparationCoordinator(creation, registry, denied, () -> 0),
            new PaymentConfirmationCoordinator(attemptPhase, registry, denied, () -> 0), digest, denied, clock);
        var error = catchThrowable(() -> checkout.create(command("lifetime", "no-metric-site")));
        assertThat(error).isInstanceOf(PaymentApplicationException.class);
        assertThat(((PaymentApplicationException) error).code()).isEqualTo("SITE_NOT_AVAILABLE");
        assertThat(((PaymentApplicationException) error).status()).isEqualTo(503);
        assertInitialEmpty();
        var c = service.create(command("lifetime", "create"));
        assertCode(() -> checkout.confirm(confirm(c.view().checkoutId(), c.checkoutToken(), "no-metric-site")), "SITE_NOT_AVAILABLE");
        assertThat(count("payment_attempts")).isZero(); assertThat(count("idempotency_records")).isEqualTo(1);
        assertThat(count("payment_events")).isEqualTo(2); assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void concurrentConfirmUsesOneDeterministicAttempt() throws Exception {
        var c = service.create(command("lifetime", "create"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var calls = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) calls.add(executor.submit(() -> { gate.await(); return service.confirm(confirm(c.view().checkoutId(), c.checkoutToken(), "concurrent")).status(); }));
            gate.countDown();
            for (var call : calls) assertThat(call.get(15, TimeUnit.SECONDS)).isEqualTo("PROCESSING");
        }
        assertThat(count("payment_attempts")).isEqualTo(1);
        assertThat(count("fake_provider_operations")).isEqualTo(2);
    }
    @Test void inProgressCreateFailsSafelyAndConfirmReturnsAuthorizedViewWithoutProvider() {
        var scope = IdempotencyRecord.Scope.publicOperation(site.value(), Stage2TelemetryPort.Operation.CHECKOUT_CREATE, null);
        var fingerprint = idempotency.fingerprint(Map.of("site_id", site.value(), "price_key", "lifetime", "origin", "https://shop.test",
            "customer_ownership", "guest"));
        var record = IdempotencyRecord.begin(UUID.randomUUID(), scope, "orphan", fingerprint, INITIAL_TIME);
        records.claim(record, INITIAL_TIME);
        var error = catchThrowable(() -> service.create(command("lifetime", "orphan")));
        assertThat(error).isInstanceOf(PaymentApplicationException.class);
        assertThat(((PaymentApplicationException) error).extensions()).containsExactly(entry("operation_id", record.id()));
        assertThat(count("payments")).isZero(); assertThat(count("fake_provider_operations")).isZero();
        var c = service.create(command("lifetime", "create"));
        var confirmScope = IdempotencyRecord.Scope.publicOperation(site.value(), Stage2TelemetryPort.Operation.CHECKOUT_CONFIRM, c.view().checkoutId());
        var confirmFingerprint = idempotency.fingerprint(Map.of("checkout_id", c.view().checkoutId(), "payment_id", c.view().paymentId(), "payment_method_family", "CARD"));
        records.claim(IdempotencyRecord.begin(UUID.randomUUID(), confirmScope, "orphan", confirmFingerprint, INITIAL_TIME), INITIAL_TIME);
        assertThat(service.confirm(confirm(c.view().checkoutId(), c.checkoutToken(), "orphan"))).isEqualTo(c.view());
        assertThat(count("payment_attempts")).isZero(); assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void authorizationAndCapabilityRejectBeforeAttemptsAndExpiredReadHasSafePaymentId() {
        var c = service.create(command("lifetime", "created"));
        assertCode(() -> service.get(c.view().checkoutId(), URI.create("https://shop.test"), "A".repeat(43)), "CHECKOUT_TOKEN_INVALID");
        jdbc.update("update sites set payment_method_policy='ALLOWLIST' where id=?", site.value());
        jdbc.update("insert into site_allowed_payment_methods(site_id,payment_method) values (?,'PAYPAL')", site.value());
        assertCode(() -> service.confirm(confirm(c.view().checkoutId(), c.checkoutToken(), "denied")), "PAYMENT_CAPABILITY_UNAVAILABLE");
        assertThat(count("payment_attempts")).isZero();
        jdbc.update("delete from site_allowed_payment_methods where site_id=?", site.value());
        jdbc.update("update sites set payment_method_policy='AUTOMATIC',status='DISABLED' where id=?", site.value());
        assertCode(() -> service.get(c.view().checkoutId(), URI.create("https://shop.test"), c.checkoutToken()), "SITE_NOT_AVAILABLE");
        jdbc.update("update sites set status='ACTIVE' where id=?", site.value());
        TestClock.set(c.view().expiresAt());
        assertCode(() -> service.get(c.view().checkoutId(), URI.create("https://shop.test"), c.checkoutToken()), "CHECKOUT_EXPIRED");
    }
    @ParameterizedTest @EnumSource(Scenario.class)
    void confirmationMapsSafeOutcomesAndActionExposesStatusOnly(Scenario scenario) {
        var c = service.create(command("lifetime", "create"));
        var payment = load.findById(site, new PaymentId(c.view().paymentId())).orElseThrow();
        state.configure(request(payment), scenario);
        var command = confirm(c.view().checkoutId(), c.checkoutToken(), "scenario");
        if (Set.of(Scenario.RECOVERABLE_DECLINE, Scenario.TERMINAL_DECLINE, Scenario.TEMPORARILY_UNAVAILABLE, Scenario.UNKNOWN).contains(scenario)) {
            var code = switch (scenario) { case TEMPORARILY_UNAVAILABLE -> "PROVIDER_TEMPORARILY_UNAVAILABLE"; case UNKNOWN -> "PROVIDER_RESULT_UNKNOWN"; default -> "PROVIDER_DECLINED"; };
            assertCode(() -> service.confirm(command), code);
            assertCode(() -> service.confirm(command), code);
        } else {
            var view = service.confirm(command);
            assertThat(view.status()).isEqualTo(switch (scenario) { case ACCEPTED -> "REQUIRES_PAYMENT_METHOD"; case REQUIRES_ACTION -> "REQUIRES_ACTION"; default -> "PROCESSING"; });
            assertThat(view.providerClientPayload()).isEmpty();
            assertThatThrownBy(() -> view.providerClientPayload().put("reference", "unsafe")).isInstanceOf(UnsupportedOperationException.class);
            assertThat(service.confirm(command)).isEqualTo(view);
        }
        assertThat(count("payment_attempts")).isEqualTo(1);
        assertThat(count("fake_provider_operations")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from payments", String.class)).isNotEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForList("select technical_metadata from payment_events").toString()).doesNotContain("action", c.checkoutToken());
    }
    @Test void differentConfirmationFingerprintConflictsAndInactiveRoutingFailsClosed() {
        var c = service.create(command("lifetime", "create"));
        service.confirm(confirm(c.view().checkoutId(), c.checkoutToken(), "same"));
        var different = new ConfirmCheckoutCommand(c.view().checkoutId(), URI.create("https://shop.test"), c.checkoutToken(),
            dev.genericpayments.platform.sites.domain.PaymentMethod.PAYPAL, "same", "request");
        assertCode(() -> service.confirm(different), "IDEMPOTENCY_CONFLICT");
        assertThat(count("payment_attempts")).isEqualTo(1);
        jdbc.update("update payment_accounts set account_status='DISABLED' where id=?", account.id().value());
        assertCode(() -> service.create(command("lifetime", "disabled-account")), "PAYMENT_CAPABILITY_UNAVAILABLE");
        jdbc.update("update payment_accounts set account_status='ACTIVE',provider='STRIPE' where id=?", account.id().value());
        assertCode(() -> service.create(command("lifetime", "unsupported-provider")), "PAYMENT_CAPABILITY_UNAVAILABLE");
        assertThat(count("payments")).isEqualTo(1); assertThat(count("idempotency_records")).isEqualTo(2);
    }
    void assertInitialEmpty() { for (var table : List.of("payments", "payment_events", "checkout_sessions", "idempotency_records", "fake_provider_operations")) assertThat(count(table)).isZero(); }
    void rejectWrite(String table, String operation, String condition) {
        jdbc.execute("create function task5_reject_write() returns trigger language plpgsql as $$ begin if " + condition + " then raise exception 'injected checkout failure' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger task5_reject_write before " + operation + " on " + table + " for each row execute function task5_reject_write()");
    }
    @AfterEach void clearWriteFailure() {
        jdbc.execute("drop trigger if exists task5_reject_write on checkout_sessions");
        jdbc.execute("drop trigger if exists task5_reject_write on idempotency_records");
        jdbc.execute("drop function if exists task5_reject_write()");
    }
    ConfirmCheckoutCommand confirm(UUID id, String token, String key) {
        return new ConfirmCheckoutCommand(id, URI.create("https://shop.test"), token, dev.genericpayments.platform.sites.domain.PaymentMethod.CARD, key, "request-2");
    }
    static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        var error = catchThrowable(call); assertThat(error).isInstanceOf(PaymentApplicationException.class);
        assertThat(((PaymentApplicationException) error).code()).isEqualTo(code);
    }
}
