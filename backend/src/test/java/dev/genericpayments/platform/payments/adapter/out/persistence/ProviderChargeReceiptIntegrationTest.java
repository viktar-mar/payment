package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

class ProviderChargeReceiptIntegrationTest extends CheckoutTestFixture {
    @Autowired ProviderChargeReceiptPort receipts;
    @Autowired ClientPaymentAttemptPort clients;
    @Autowired SavePaymentAttemptPort saveAttempts;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    Payment p;
    @BeforeEach void stripePayment() {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        p=save.save(payment().withProviderPaymentReference("pi_fixture",INITIAL_TIME));
    }
    ClientPaymentAttemptPort.Baseline baseline(int number,String failed) {
        var a=saveAttempts.insert(PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),number,"client_"+UUID.randomUUID(),"CARD",INITIAL_TIME),INITIAL_TIME);
        return new ClientPaymentAttemptPort.Baseline(a.value().id().value(),account.id(),p.id(),p.customerId(),"stripe-sandbox-default","acct_expected",failed);
    }
    @Test void duplicateVerifiedChargeIsOneImmutableReceipt() {
        assertThat(receipts.record(account.id(),p.id(),"ch_once",true,"SUCCEEDED")).isTrue();
        assertThat(receipts.record(account.id(),p.id(),"ch_once",true,"SUCCEEDED")).isFalse();
        assertThat(receipts.contains(account.id(),"ch_once")).isTrue();
        assertThat(receipts.contains(new PaymentAccountId(UUID.randomUUID()),"ch_once")).isFalse();
        assertThat(count("provider_charge_receipts")).isEqualTo(1);
        assertThatThrownBy(() -> receipts.record(account.id(),p.id(),"ch_once",false,"FAILED")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("update provider_charge_receipts set paid=false,safe_outcome='FAILED' where charge_reference='ch_once'"))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("delete from provider_charge_receipts where charge_reference='ch_once'"))
            .isInstanceOf(RuntimeException.class);
    }
    @Test void foreignPaymentAndAccountCannotOwnCharge() {
        assertThatThrownBy(() -> receipts.record(account.id(),new PaymentId(UUID.randomUUID()),"ch_missing",true,"SUCCEEDED")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> receipts.record(new PaymentAccountId(UUID.randomUUID()),p.id(),"ch_foreign",true,"SUCCEEDED")).isInstanceOf(RuntimeException.class);
        var other=save.save(payment());
        receipts.record(account.id(),p.id(),"ch_bound",true,"SUCCEEDED");
        assertThatThrownBy(() -> receipts.record(account.id(),other.id(),"ch_bound",true,"SUCCEEDED")).isInstanceOf(RuntimeException.class);
        assertThat(count("provider_charge_receipts")).isEqualTo(1);
        var otherAccount=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'other-account','STRIPE','SANDBOX','ACTIVE','acct_other','other-alias',?,0)",otherAccount,java.sql.Timestamp.from(INITIAL_TIME));
        assertThatThrownBy(() -> receipts.record(new PaymentAccountId(otherAccount),p.id(),"ch_other",true,"SUCCEEDED")).isInstanceOf(RuntimeException.class);
    }
    @Test void unsafeOutcomeOrReferenceCannotBePersisted() {
        assertThatThrownBy(() -> receipts.record(account.id(),p.id(),"ch_safe",false,"raw email user@example.test")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> receipts.record(account.id(),p.id(),"raw secret",false,"FAILED")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> receipts.record(account.id(),p.id(),"ch_safe",true,"FAILED")).isInstanceOf(RuntimeException.class);
    }
    @Test void baselineReplayIsIdempotentAndConflictingReplayCannotReplaceWatermark() {
        var b=baseline(1,"ch_old");
        assertThat(clients.record(b)).isTrue();assertThat(clients.record(b)).isFalse();
        assertThat(clients.findByAttempt(b.attemptId())).contains(b);
        var changed=new ClientPaymentAttemptPort.Baseline(b.attemptId(),b.accountId(),b.paymentId(),b.customerId(),b.credentialsRef(),b.externalAccountId(),"ch_new");
        assertThatThrownBy(() -> clients.record(changed)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("update payment_client_attempts set initial_failed_charge_reference='ch_new' where attempt_id=?",b.attemptId())).isInstanceOf(RuntimeException.class);
        assertThat(clients.findByAttempt(b.attemptId())).contains(b);
    }
    @Test void baselineRejectsForeignAttemptPaymentAccountAndCustomer() {
        var b=baseline(1,null);var other=save.save(payment());
        for(var invalid:java.util.List.of(
                new ClientPaymentAttemptPort.Baseline(UUID.randomUUID(),b.accountId(),b.paymentId(),null,b.credentialsRef(),b.externalAccountId(),null),
                new ClientPaymentAttemptPort.Baseline(b.attemptId(),b.accountId(),other.id(),null,b.credentialsRef(),b.externalAccountId(),null),
                new ClientPaymentAttemptPort.Baseline(b.attemptId(),new PaymentAccountId(UUID.randomUUID()),b.paymentId(),null,b.credentialsRef(),b.externalAccountId(),null),
                new ClientPaymentAttemptPort.Baseline(b.attemptId(),b.accountId(),b.paymentId(),UUID.randomUUID(),b.credentialsRef(),b.externalAccountId(),null),
                new ClientPaymentAttemptPort.Baseline(b.attemptId(),b.accountId(),b.paymentId(),null,"other-alias",b.externalAccountId(),null),
                new ClientPaymentAttemptPort.Baseline(b.attemptId(),b.accountId(),b.paymentId(),null,b.credentialsRef(),"acct_foreign",null))) {
            assertThatThrownBy(() -> clients.record(invalid)).isInstanceOf(RuntimeException.class);
        }
        assertThat(clients.findByAttempt(b.attemptId())).isEmpty();
    }
    @Test void previousDeclineWatermarkDoesNotInventNewAttemptOrFinancialFact() {
        receipts.record(account.id(),p.id(),"ch_old",false,"FAILED");
        var first=baseline(1,null);clients.record(first);
        var second=baseline(2,"ch_old");clients.record(second);
        assertThat(clients.findByAttempt(second.attemptId()).orElseThrow().initialFailedChargeReference()).isEqualTo("ch_old");
        assertThat(receipts.record(account.id(),p.id(),"ch_old",false,"FAILED")).isFalse();
        assertThat(count("provider_charge_receipts")).isEqualTo(1);assertThat(count("payment_attempts")).isEqualTo(2);
        assertThat(attempts.findById(site,new PaymentAttemptId(second.attemptId())).orElseThrow().value().status()).isEqualTo(PaymentAttemptStatus.CREATED);
        assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();
    }
    @Test void accountScopedLookupCannotReadForeignPayment() {
        assertThat(load.findByAccountAndId(account.id(),p.id())).contains(p);
        assertThat(load.findByAccountAndId(new PaymentAccountId(UUID.randomUUID()),p.id())).isEmpty();
    }
    @Test void clientAttemptQueryPreservesLegacyAttemptsAndSiteIsolation() {
        var legacy=baseline(1,null);var client=baseline(2,"ch_old");clients.record(client);
        assertThat(attempts.findByPayment(site,p.id())).hasSize(2);
        assertThat(attempts.findClientAttemptsByPayment(site,p.id())).extracting(a -> a.value().id().value()).containsExactly(client.attemptId());
        assertThat(attempts.findClientAttemptsByPayment(new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),p.id())).isEmpty();
        assertThat(clients.findByAttempt(legacy.attemptId())).isEmpty();
    }
    @Test void nullableAndPresentOriginalCustomerRemainImmutableSnapshots() {
        var customer=UUID.randomUUID();
        jdbc.update("insert into customers(id,site_id,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)",customer,site.value(),java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("update payments set customer_id=? where id=?",customer,p.id().value());
        p=load.findById(site,p.id()).orElseThrow();var b=baseline(1,null);
        assertThat(clients.record(b)).isTrue();assertThat(clients.findByAttempt(b.attemptId()).orElseThrow().customerId()).isEqualTo(customer);
        var omitted=new ClientPaymentAttemptPort.Baseline(b.attemptId(),b.accountId(),b.paymentId(),null,b.credentialsRef(),b.externalAccountId(),null);
        assertThatThrownBy(() -> clients.record(omitted)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("update payment_client_attempts set customer_id=null where attempt_id=?",b.attemptId())).isInstanceOf(RuntimeException.class);
    }
    @Test void concurrentIdenticalDeliveryInsertsOneReceiptAndOneBaseline() throws Exception {
        var b=baseline(1,null);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> delivery=() -> {ready.countDown();assertThat(start.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return receipts.record(account.id(),p.id(),"ch_concurrent",false,"FAILED");};
            var one=pool.submit(delivery);var two=pool.submit(delivery);
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(java.util.List.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            var first=pool.submit(() -> clients.record(b));var second=pool.submit(() -> clients.record(b));
            assertThat(java.util.List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count("provider_charge_receipts")).isEqualTo(1);assertThat(count("payment_client_attempts")).isEqualTo(1);
    }
    @Test void receiptAndBaselineJoinCallerRollbackSoFailedApplicationCannotConsumeEvidence() {
        var b=baseline(1,null);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(receipts.record(account.id(),p.id(),"ch_rollback",false,"FAILED")).isTrue();
            assertThat(clients.record(b)).isTrue();tx.setRollbackOnly();
        });
        assertThat(receipts.contains(account.id(),"ch_rollback")).isFalse();assertThat(clients.findByAttempt(b.attemptId())).isEmpty();
        assertThat(receipts.record(account.id(),p.id(),"ch_rollback",false,"FAILED")).isTrue();
    }
}
