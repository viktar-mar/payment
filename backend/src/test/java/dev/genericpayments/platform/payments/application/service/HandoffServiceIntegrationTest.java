package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import java.time.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class HandoffServiceIntegrationTest extends PartnerTestFixture {
    @Autowired DynamicPaymentService dynamic; @Autowired HandoffService handoffs;
    @Autowired DigestPort digest; @Autowired IdempotencyService idem; @Autowired IdempotencyPort records;
    @Autowired DynamicPaymentTransaction phases; @Autowired LoadPaymentAccountPort accounts; @Autowired Stage2TelemetryPort telemetry;
    @Autowired Clock clock; @Autowired CheckoutAuthorizationService authorization;
    @Test void dynamicCreationAtomicallyCreatesTenMinuteHandoffAndEncryptedReplay() {
        var created = dynamic.create(dynamic("order", "create"));
        assertThat(created.handoffExpiresAt()).isEqualTo(INITIAL_TIME.plusSeconds(600));
        assertThat(Base64.getUrlDecoder().decode(created.handoffToken())).hasSize(32);
        assertThat(jdbc.queryForObject("select token_hash from checkout_sessions", String.class)).isEqualTo(digest.sha256Hex(created.handoffToken()));
        assertThat(jdbc.queryForObject("select scopes::text from checkout_sessions", String.class)).isEqualTo("[]");
        assertThat(jdbc.queryForObject("select scope from idempotency_records", String.class)).isEqualTo("partner:" + site.value() + ":payments:create");
        assertThat(jdbc.queryForList("select * from checkout_sessions").toString()).doesNotContain(created.handoffToken());
        assertThat(jdbc.queryForList("select * from idempotency_records").toString()).doesNotContain(created.handoffToken());
        assertThat(created.toString()).doesNotContain(created.handoffToken());
        assertThat(dynamic.create(dynamic("order", "create"))).isEqualTo(created);
    }
    @Test void metadataIsCanonicalStrictUtf8AndIncludesEveryBusinessField() {
        var base = dynamic("order", "create");
        var accepted = new CreateDynamicPaymentCommand(site, "order", 2300, "EUR", base.checkoutOrigin(), null, Map.of("a.b", "é", "z", "value"), "create", "request");
        var first = dynamic.create(accepted);
        var reversed = new LinkedHashMap<String,String>(); reversed.put("z", "value"); reversed.put("a.b", "é");
        assertThat(dynamic.create(new CreateDynamicPaymentCommand(site, "order", 2300, "EUR", base.checkoutOrigin(), null, reversed, "create", "different-request"))).isEqualTo(first);
        for (var metadata : List.of(Map.of("key", "\ud800"), Map.of("k".repeat(65), "v"), Map.of("key", "v".repeat(501)), Map.of("a", "é".repeat(500), "b", "é".repeat(500), "c", "é".repeat(500), "d", "é".repeat(500), "e", "é".repeat(40))))
            assertThatThrownBy(() -> dynamic.create(new CreateDynamicPaymentCommand(site, "bad", 1, "EUR", base.checkoutOrigin(), null, metadata, UUID.randomUUID().toString(), "request"))).isInstanceOf(RuntimeException.class);
        var twentyOne = new HashMap<String,String>(); for (int i=0;i<21;i++) twentyOne.put("k"+i,"v");
        assertThatThrownBy(() -> dynamic.create(new CreateDynamicPaymentCommand(site, "bad", 1, "EUR", base.checkoutOrigin(), null, twentyOne, "bad", "request"))).isInstanceOf(RuntimeException.class);
        for (var changed : List.of(new CreateDynamicPaymentCommand(site,"order",2301,"EUR",base.checkoutOrigin(),null,reversed,"create","request"), new CreateDynamicPaymentCommand(site,"order",2300,"USD",base.checkoutOrigin(),null,reversed,"create","request"), new CreateDynamicPaymentCommand(site,"order",2300,"EUR",base.checkoutOrigin(),"https://shop.test/done",reversed,"create","request"), new CreateDynamicPaymentCommand(site,"order",2300,"EUR",base.checkoutOrigin(),null,Map.of("z","changed"),"create","request"))) code(() -> dynamic.create(changed), "IDEMPOTENCY_CONFLICT");
        assertThat(count("payments")).isEqualTo(1);
    }
    @Test void expiredCreateAndIssueReplayPreservePaymentAndNeverMintReplacement() {
        var created = dynamic.create(dynamic("order", "create"));
        var issued = handoffs.issue(site, new PaymentId(created.paymentId()), URI.create("https://shop.test"), "issue", "request");
        TestClock.set(INITIAL_TIME.plusSeconds(600));
        code(() -> dynamic.create(dynamic("order", "create")), "CHECKOUT_EXPIRED");
        code(() -> handoffs.issue(site, new PaymentId(created.paymentId()), URI.create("https://shop.test"), "issue", "request"), "CHECKOUT_EXPIRED");
        assertThat(count("payments")).isEqualTo(1); assertThat(count("checkout_sessions")).isEqualTo(2);
        assertThat(jdbc.queryForList("select response_secret_state from idempotency_records", String.class)).containsOnly("EXPIRED");
        assertThat(issued.handoffToken()).isNotEqualTo(created.handoffToken());
    }
    @Test void inProgressCreateReturnsOnlyDurableOperationIdentityWithoutBusinessSideEffects() {
        var command=dynamic("order","pending");
        var fields=Map.<String,Object>of("external_order_id",command.externalOrderId(),"amount_minor",command.amountMinor(),"currency",command.currency(),
            "checkout_origin",command.checkoutOrigin().toString(),"return_url_present",true,"return_url",command.returnUrl(),"metadata",command.metadata());
        var candidate=IdempotencyRecord.begin(UUID.randomUUID(),IdempotencyRecord.Scope.partner(site.value(),Stage2TelemetryPort.Operation.PAYMENT_CREATE),"pending",idem.fingerprint(fields),INITIAL_TIME);
        records.claim(candidate,INITIAL_TIME);
        var error=catchThrowable(() -> dynamic.create(command));
        assertThat(error).isInstanceOf(PaymentApplicationException.class);
        assertThat(((PaymentApplicationException)error).code()).isEqualTo("PAYMENT_STATE_CONFLICT");
        assertThat(((PaymentApplicationException)error).extensions()).containsOnlyKeys("operation_id");
        assertThat(((PaymentApplicationException)error).extensions().get("operation_id")).isEqualTo(candidate.id());
        assertThat(count("payments")).isZero(); assertThat(count("checkout_sessions")).isZero(); assertThat(count("fake_provider_operations")).isZero();
    }
    @Test void exchangeConsumesOnlyOnceAndCreatesFreshTwentyFourHourCheckoutWithoutProvider() {
        var created = dynamic.create(dynamic("order", "create"));
        var checkout = handoffs.exchange(created.handoffToken(), URI.create("https://shop.test"), "127.0.0.1");
        assertThat(checkout.view().expiresAt()).isEqualTo(INITIAL_TIME.plus(Duration.ofHours(24)));
        assertThat(checkout.view().providerClientPayload()).isEmpty();
        assertThat(checkout.checkoutToken()).isNotEqualTo(created.handoffToken());
        assertThat(authorization.authorize(checkout.view().checkoutId(), URI.create("https://shop.test"), checkout.checkoutToken(), "confirm").payment().id().value()).isEqualTo(created.paymentId());
        code(() -> handoffs.exchange(created.handoffToken(), URI.create("https://shop.test"), "127.0.0.1"), "CHECKOUT_TOKEN_INVALID");
        assertThat(count("checkout_sessions")).isEqualTo(2); assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void malformedExpiredWrongOriginAndInactiveSiteExchangeCreateNothing() {
        var created = dynamic.create(dynamic("order", "create"));
        for (var token : List.of("bad", "A".repeat(43), created.handoffToken()+"x")) code(() -> handoffs.exchange(token, URI.create("https://shop.test"), "127.0.0.1"), "CHECKOUT_TOKEN_INVALID");
        code(() -> handoffs.exchange(created.handoffToken(), URI.create("https://evil.test"), "127.0.0.1"), "CHECKOUT_TOKEN_INVALID");
        jdbc.update("update sites set status='DISABLED' where id=?", site.value()); code(() -> handoffs.exchange(created.handoffToken(), URI.create("https://shop.test"), "127.0.0.1"), "CHECKOUT_TOKEN_INVALID");
        jdbc.update("update sites set status='ACTIVE' where id=?", site.value()); TestClock.set(INITIAL_TIME.plusSeconds(600));
        code(() -> handoffs.exchange(created.handoffToken(), URI.create("https://shop.test"), "127.0.0.1"), "CHECKOUT_TOKEN_INVALID");
        assertThat(count("checkout_sessions")).isEqualTo(1);
    }
    @Test void concurrentExchangeHasOneWinnerAndOneStableInvalidToken() throws Exception {
        var created = dynamic.create(dynamic("order", "create")); var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<String>>();
            for (int i=0;i<2;i++) results.add(executor.submit(() -> { gate.await(); try { handoffs.exchange(created.handoffToken(), URI.create("https://shop.test"), "127.0.0.1"); return "success"; } catch (PaymentApplicationException failure) { return failure.code(); } }));
            gate.countDown(); assertThat(List.of(results.get(0).get(15,TimeUnit.SECONDS),results.get(1).get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("success", "CHECKOUT_TOKEN_INVALID");
        }
        assertThat(count("checkout_sessions")).isEqualTo(2);
    }
    @Test void checkoutInsertionFailureRollsBackConsumption() {
        var created = dynamic.create(dynamic("order", "create")); rejectWrite("checkout_sessions", "insert", "new.session_type='CHECKOUT'");
        assertThatThrownBy(() -> handoffs.exchange(created.handoffToken(), URI.create("https://shop.test"), "127.0.0.1")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select consumed_at from checkout_sessions", java.sql.Timestamp.class)).isNull();
        clearWriteFailure(); assertThat(handoffs.exchange(created.handoffToken(), URI.create("https://shop.test"), "127.0.0.1").checkoutToken()).isNotBlank();
    }
    @Test void createJournalSessionAndCompletionFailuresRollbackAllInitialFacts() {
        rejectJournal(PaymentEventType.PAYMENT_CREATED); assertThatThrownBy(() -> dynamic.create(dynamic("order", "create"))).isInstanceOf(RuntimeException.class); empty(); removeJournalFailure();
        for (var table : List.of("checkout_sessions", "idempotency_records")) { rejectWrite(table, table.equals("checkout_sessions") ? "insert" : "update", "true"); assertThatThrownBy(() -> dynamic.create(dynamic("order", "create"))).isInstanceOf(RuntimeException.class); empty(); clearWriteFailure(); }
    }
    @Test void issueSessionAndCompletionFailuresRollbackBothHandoffAndClaim() {
        var created=dynamic.create(dynamic("order","create"));
        for (var table:List.of("checkout_sessions","idempotency_records")) {
            rejectWrite(table,table.equals("checkout_sessions") ? "insert" : "update","true");
            assertThatThrownBy(() -> handoffs.issue(site,new PaymentId(created.paymentId()),URI.create("https://shop.test"),"issue","request"))
                .isInstanceOf(RuntimeException.class);
            assertThat(count("checkout_sessions")).isEqualTo(1); assertThat(count("idempotency_records")).isEqualTo(1);
            clearWriteFailure();
        }
        assertThat(handoffs.issue(site,new PaymentId(created.paymentId()),URI.create("https://shop.test"),"issue","request").handoffToken()).isNotBlank();
    }
    @Test void concurrentDynamicRetriesHaveOnePaymentHandoffAndProviderReceipt() throws Exception {
        var gate=new CountDownLatch(1);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(() -> { gate.await(); return dynamic.create(dynamic("order","same")); });
            var second=executor.submit(() -> { gate.await(); return dynamic.create(dynamic("order","same")); });
            gate.countDown(); assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));
        }
        assertThat(count("payments")).isEqualTo(1); assertThat(count("checkout_sessions")).isEqualTo(1);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    void empty() { for (var table : List.of("payments","payment_events","checkout_sessions","idempotency_records","fake_provider_operations")) assertThat(count(table)).isZero(); }
    @Test void issueIsSubjectScopedAndRefusesForeignOrTerminalPayments() {
        var first = dynamic.create(dynamic("first", "first")); var second = dynamic.create(dynamic("second", "second"));
        var a = handoffs.issue(site,new PaymentId(first.paymentId()),URI.create("https://shop.test"),"same","request");
        var b = handoffs.issue(site,new PaymentId(second.paymentId()),URI.create("https://shop.test"),"same","request");
        assertThat(a.handoffToken()).isNotEqualTo(b.handoffToken());
        assertThat(handoffs.issue(site,new PaymentId(first.paymentId()),URI.create("https://shop.test"),"same","request")).isEqualTo(a);
        code(() -> handoffs.issue(new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),new PaymentId(first.paymentId()),URI.create("https://shop.test"),"foreign","request"), "SITE_NOT_AVAILABLE");
        jdbc.update("update payments set status='SUCCEEDED' where id=?",first.paymentId());
        code(() -> handoffs.issue(site,new PaymentId(first.paymentId()),URI.create("https://shop.test"),"terminal","request"), "PAYMENT_STATE_CONFLICT");
    }
    @Test void telemetryPreflightFailsBeforeAnyDurableSideEffect() {
        jdbc.update("update sites set site_key='unconfigured-site' where id=?",site.value());
        code(() -> dynamic.create(dynamic("order","create")), "SITE_NOT_AVAILABLE"); empty();
    }
    @Test void preparationRunsOutsideTransactionsAfterFactsAndCompletionCommit() {
        var probe = new DelegatingProvider(fake) {
            @Override public ProviderResult preparePayment(ProviderPaymentRequest r) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(count("payments")).isEqualTo(1); assertThat(count("checkout_sessions")).isEqualTo(1);
                assertThat(jdbc.queryForObject("select status from idempotency_records",String.class)).isEqualTo("COMPLETED");
                return super.preparePayment(r);
            }
        };
        var registry = new PaymentProviderRegistry(Map.of(account.provider(), probe));
        var coordinator = new PaymentPreparationCoordinator(creation, registry, telemetry, System::nanoTime);
        var service = new DynamicPaymentService(phases, handoffs, load, accounts, registry, coordinator, telemetry);
        assertThat(service.create(dynamic("order","create")).status()).isEqualTo("REQUIRES_PAYMENT_METHOD");
    }
    @Test void acknowledgementRollbackResumesDeterministicPreparationAndOriginalSecret() {
        rejectJournal(PaymentEventType.PAYMENT_METHOD_REQUIRED);
        assertThatThrownBy(() -> dynamic.create(dynamic("order","create"))).isInstanceOf(RuntimeException.class);
        assertThat(count("checkout_sessions")).isEqualTo(1); assertThat(count("fake_provider_operations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from payments",String.class)).isEqualTo("CREATED");
        removeJournalFailure(); var resumed = dynamic.create(dynamic("order","create"));
        assertThat(resumed.status()).isEqualTo("REQUIRES_PAYMENT_METHOD"); assertThat(count("fake_provider_operations")).isEqualTo(1);
        assertThat(dynamic.create(dynamic("order","create"))).isEqualTo(resumed);
    }
}
