package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripePaymentProviderAdapterTest;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.io.IOException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;

@ActiveProfiles("test")
@TestPropertySource(properties = {
    "payment.stripe.sandbox.enabled=true",
    "payment.stripe.sandbox.secret-key=sk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "payment.stripe.sandbox.publishable-key=pk_test_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
    "payment.stripe.sandbox.webhook-secret=whsec_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"})
@Import(StripePaymentProviderAdapterTest.FixtureConfiguration.class)
class StripePreparationIntegrationTest extends CheckoutTestFixture {
    @Autowired PaymentPreparationCoordinator coordinator;
    @Autowired PaymentProviderRegistry registry;
    @Autowired StripePaymentProviderAdapterTest.FixtureFactory factory;
    PaymentAccount stripeAccount;
    StripeHttpFixture http;
    @BeforeEach void stripe() throws IOException {
        http = new StripeHttpFixture(); factory.http = http;
        stripeAccount = PaymentAccount.create(account.id(), "test-account", PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, "acct_expected", "stripe-sandbox-default");
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
    }
    @AfterEach void closeHttp() { http.close(); }
    @Override protected PreparePaymentCommand prepare(Payment p) {
        return new PreparePaymentCommand(p, stripeAccount, "test-site", "request-1", "create-1");
    }

    @Test void committedLocalPaymentPrecedesProviderAndNeverBecomesSynchronousSuccess() {
        var p = payment(); identity(); intent();
        var prepared = coordinator.prepare(prepare(p));
        assertThat(prepared.providerPaymentReference()).isEqualTo("pi_fixture");
        assertThat(prepared.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(events.findByPayment(site, p.id())).extracting(PaymentEvent::type)
            .containsExactly(PaymentEventType.PAYMENT_CREATED, PaymentEventType.PAYMENT_METHOD_REQUIRED);
        identity(); assertThat(coordinator.prepare(prepare(p))).isEqualTo(prepared);
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
    }

    @Test void lostMappingCommitRecoversAcknowledgedReceiptWithoutAnotherPost() {
        var p = payment(); rejectJournal(PaymentEventType.PAYMENT_METHOD_REQUIRED); identity(); intent();
        assertThatThrownBy(() -> coordinator.prepare(prepare(p))).isInstanceOf(RuntimeException.class);
        assertThat(load.findById(site, p.id()).orElseThrow().providerPaymentReference()).isNull();
        assertThat(jdbc.queryForObject("select provider_reference from provider_operations", String.class)).isEqualTo("pi_fixture");
        var receipt = jdbc.queryForList("select * from provider_operations");
        removeJournalFailure(); identity();
        assertThat(coordinator.prepare(prepare(p)).providerPaymentReference()).isEqualTo("pi_fixture");
        assertThat(jdbc.queryForList("select * from provider_operations")).isEqualTo(receipt);
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
    }

    @Test void enabledStripeStillRoutesFakePreparationWithoutHttp() {
        jdbc.update("update payment_accounts set provider='FAKE',external_account_id='fake-account',credentials_ref='config-ref' where id=?", account.id().value());
        var p = payment();
        assertThat(coordinator.prepare(new PreparePaymentCommand(p, account, "test-site", "request", "idempotency"))
            .providerPaymentReference()).startsWith("fake_");
        assertThat(count("provider_operations")).isZero(); assertThat(http.calls()).isEmpty();
    }

    @Test void sameReferenceLateStripeAcknowledgementDoesNotRegressAdvancedStateOrAppendEvents() {
        var p = payment(); creation.createLocal(prepare(p));
        var advanced = save.save(load.findById(site, p.id()).orElseThrow().withProviderPaymentReference("pi_fixture", INITIAL_TIME).processing(INITIAL_TIME));
        var before = events.findByPayment(site, p.id());
        assertThat(creation.recordPreparedOrUnknown(prepare(p), new ProviderResult.Accepted("pi_fixture", false)).payment()).isEqualTo(advanced);
        assertThat(events.findByPayment(site, p.id())).isEqualTo(before);
    }

    @Test void lateAcknowledgementRejectsMissingAndConflictingReference() {
        var missing = payment(); creation.createLocal(prepare(missing)); save.save(missing.processing(INITIAL_TIME));
        assertThatThrownBy(() -> creation.recordPreparedOrUnknown(prepare(missing), new ProviderResult.Accepted("pi_fixture", false)))
            .isInstanceOf(IllegalStateException.class);
        var bound = payment(); creation.createLocal(prepare(bound)); save.save(bound.withProviderPaymentReference("pi_original", INITIAL_TIME).processing(INITIAL_TIME));
        assertThatThrownBy(() -> creation.recordPreparedOrUnknown(prepare(bound), new ProviderResult.Accepted("pi_other", false)))
            .isInstanceOf(RuntimeException.class);
        assertThat(events.findByPayment(site, missing.id())).hasSize(1);
        assertThat(events.findByPayment(site, bound.id())).hasSize(1);
    }

    @Test void lateSameReferenceStillRequiresImmutableCommandAndAccount() {
        var p = payment(); creation.createLocal(prepare(p));
        save.save(p.withProviderPaymentReference("pi_fixture", INITIAL_TIME).processing(INITIAL_TIME));
        var altered = Payment.dynamic(p.id(), site, account.id(), p.externalOrderId(),
            dev.genericpayments.platform.catalog.domain.Money.of(2001, "EUR"), p.taxMode(), p.technicalMetadata(), INITIAL_TIME);
        assertThatThrownBy(() -> creation.recordPreparedOrUnknown(prepare(altered), new ProviderResult.Accepted("pi_fixture", false)))
            .isInstanceOf(RuntimeException.class);
        var wrong = PaymentAccount.create(account.id(), "test-account", PaymentProviderType.STRIPE, PaymentAccountMode.SANDBOX, "acct_other", "stripe-sandbox-default");
        assertThatThrownBy(() -> creation.recordPreparedOrUnknown(new PreparePaymentCommand(p, wrong, "test-site", "r", "i"),
            new ProviderResult.Accepted("pi_fixture", false))).isInstanceOf(RuntimeException.class);
        assertThat(events.findByPayment(site, p.id())).hasSize(1);
    }

    @Test void liveLocalLeaseFencesLateSameReferenceEvenForDuplicateAcknowledgement() {
        var p = payment(); identity(); intent(); coordinator.prepare(prepare(p));
        load.tryAcquireReconciliationLease(site, p.id(), "owner", INITIAL_TIME, INITIAL_TIME.plusSeconds(60));
        assertThatThrownBy(() -> creation.recordPreparedOrUnknown(prepare(p), new ProviderResult.Accepted("pi_fixture", false)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(events.findByPayment(site, p.id())).hasSize(2);
    }

    @Test void fakeDuplicateAcknowledgementKeepsExistingEarlyIdempotenceWithLocalLease() {
        jdbc.update("update payment_accounts set provider='FAKE',external_account_id='fake-account',credentials_ref='config-ref' where id=?", account.id().value());
        var p = payment(); var c = new PreparePaymentCommand(p, account, "test-site", "request", "idempotency");
        var prepared = coordinator.prepare(c);
        load.tryAcquireReconciliationLease(site, p.id(), "owner", INITIAL_TIME, INITIAL_TIME.plusSeconds(60));
        assertThat(creation.recordPreparedOrUnknown(c, new ProviderResult.Accepted(prepared.providerPaymentReference(), false)).changed()).isFalse();
        assertThat(events.findByPayment(site, p.id())).hasSize(2);
    }

    void identity() { http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_expected\",\"object\":\"account\"}"); }
    void intent() { http.enqueue("POST", "/v1/payment_intents", 200,
        "{\"id\":\"pi_fixture\",\"object\":\"payment_intent\",\"livemode\":false,\"status\":\"requires_payment_method\"}"); }
}
