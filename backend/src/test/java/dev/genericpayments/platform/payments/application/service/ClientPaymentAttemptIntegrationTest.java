package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.port.out.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ClientPaymentAttemptIntegrationTest extends CheckoutTestFixture {
    @Autowired ClientPaymentAttemptTransaction clientAttempts;
    @Autowired CheckoutAuthorizationService authorization;
    @Autowired CheckoutSessionPort sessions;
    @Autowired ClientPaymentAttemptPort baselines;
    @Autowired DigestPort digest;
    @Autowired RandomTokenPort tokens;
    @Autowired IdempotencyService idempotency;
    @Autowired PlatformTransactionManager transactions;
    PaymentAccount stripe;
    Payment payment;
    CheckoutSession checkout;
    final Map<UUID,String> sessionTokens=new HashMap<>();
    @BeforeEach void clientContext() {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        stripe=PaymentAccount.create(account.id(),"test-account",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_expected","stripe-sandbox-default");
        payment=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME).requiresPaymentMethod(INITIAL_TIME));
        checkout=session(payment);
    }
    CheckoutSession session(Payment p) {
        var token=tokens.issue(32);
        var session=new CheckoutSession(UUID.randomUUID(),p.id(),site,digest.sha256Hex(token),URI.create("https://shop.test"),Set.of("read","confirm"),INITIAL_TIME.plusSeconds(3600),INITIAL_TIME);
        sessions.save(session);sessionTokens.put(session.id(),token);return session;
    }
    CheckoutAuthorizationService.Authorized authorized(CheckoutSession session) { return authorization.authorize(session.id(),session.origin(),sessionTokens.get(session.id()),"confirm"); }

    @Test void concurrentTwoKeysReserveOneAttemptAndImmutableFirstFailedCharge() throws Exception {
        var authorized=authorized(checkout);var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=executor.submit(() -> {start.await();return clientAttempts.begin(authorized,stripe,"first","request","ch_old");});
            var two=executor.submit(() -> {start.await();return clientAttempts.begin(authorized,stripe,"second","request","ch_old");});
            start.countDown();var a=one.get(10,TimeUnit.SECONDS);var b=two.get(10,TimeUnit.SECONDS);
            assertThat(a.id()).isEqualTo(b.id());
            assertThat(clientAttempts.begin(authorized,stripe,"third","request","ch_new").id()).isEqualTo(a.id());
            assertThat(baselines.findByAttempt(a.id().value()).orElseThrow().initialFailedChargeReference()).isEqualTo("ch_old");
        }
        assertThat(count("payment_attempts")).isEqualTo(1);assertThat(count("payment_client_attempts")).isEqualTo(1);
        assertThat(count("payment_events")).isEqualTo(1);assertThat(count("idempotency_records")).isEqualTo(3);
        assertThat(count("provider_charge_receipts")).isZero();
    }

    @Test void journalFailureRollsBackAttemptBaselineAndIdempotencyReceipt() {
        rejectJournal(PaymentEventType.ATTEMPT_CREATED);
        assertThatThrownBy(() -> clientAttempts.begin(authorized(checkout),stripe,"key","request","ch_old")).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_attempts")).isZero();assertThat(count("payment_client_attempts")).isZero();
        assertThat(count("idempotency_records")).isZero();assertThat(count("payment_events")).isZero();
        removeJournalFailure();
        assertThat(clientAttempts.begin(authorized(checkout),stripe,"key","request","ch_old").status()).isEqualTo(PaymentAttemptStatus.REQUIRES_PAYMENT_METHOD);
    }

    @Test void sameKeyIsIsolatedByCheckoutAndByConfirmOperation() {
        var otherPayment=save.save(payment().withProviderPaymentReference("pi_other",INITIAL_TIME).requiresPaymentMethod(INITIAL_TIME));
        var other=session(otherPayment);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var scope=Scope.publicOperation(site.value(),Stage2TelemetryPort.Operation.CHECKOUT_CONFIRM,checkout.id());
            var owner=(IdempotencyService.Owner)idempotency.begin(scope,"same",idempotency.fingerprint(Map.of("test","server-confirm")));
            idempotency.complete(owner,new Resource(ResourceType.CHECKOUT,checkout.id()),200,null);
        });
        var a=clientAttempts.begin(authorized(checkout),stripe,"same","request",null);
        var b=clientAttempts.begin(authorized(other),stripe,"same","request",null);
        assertThat(a.id()).isNotEqualTo(b.id());assertThat(count("idempotency_records")).isEqualTo(3);
        assertThat(clientAttempts.begin(authorized(checkout),stripe,"same","request",null).id()).isEqualTo(a.id());
    }

    @Test void finalAttemptAndLiveLeaseRefuseNewReservation() {
        var authorized=authorized(checkout);
        load.tryAcquireReconciliationLease(site,payment.id(),"owner",INITIAL_TIME,INITIAL_TIME.plusSeconds(60));
        assertThatThrownBy(() -> clientAttempts.begin(authorized,stripe,"locked","request",null)).isInstanceOf(RuntimeException.class);
        assertThat(count("idempotency_records")).isZero();
    }

    @Test void completedOrUnknownAttemptCannotBeReplayedAsNewClientFlow() {
        var a=clientAttempts.begin(authorized(checkout),stripe,"key","request",null);
        jdbc.update("update payment_attempts set status='UNKNOWN' where id=?",a.id().value());
        assertThatThrownBy(() -> clientAttempts.begin(authorized(checkout),stripe,"new-key","request",null)).isInstanceOf(RuntimeException.class);
        jdbc.update("update payment_attempts set status='SUCCEEDED' where id=?",a.id().value());
        jdbc.update("update payments set status='SUCCEEDED' where id=?",payment.id().value());
        assertThatThrownBy(() -> clientAttempts.begin(authorized(checkout),stripe,"key","request",null)).isInstanceOf(RuntimeException.class);
        assertThat(count("payment_attempts")).isEqualTo(1);assertThat(count("idempotency_records")).isEqualTo(1);
    }
}
