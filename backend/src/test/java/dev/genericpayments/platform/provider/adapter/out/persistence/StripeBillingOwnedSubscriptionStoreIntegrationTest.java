package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Hosted PostgreSQL only. Local permission is compilation, never execution. Fixtures are negative/store tests. */
class StripeBillingOwnedSubscriptionStoreIntegrationTest extends ProviderTestFixture {
    @Autowired StripeBillingClockStore clocks;
    @Autowired StripeBillingClockSubscriptionStore owned;
    @Autowired ProviderOperationStore operations;
    @Autowired StripeBillingBindingPort bindings;
    @Autowired PlatformTransactionManager transactions;
    PaymentAccount billing;
    CustomerId customer=new CustomerId(UUID.randomUUID());
    ReadyClock ready;
    UUID subscription,operation;
    @BeforeEach void fixture() {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_clock',credentials_ref='clock-alias' where id=?",account.id().value());
        billing=PaymentAccount.create(account.id(),account.key(),PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_clock","clock-alias");
        jdbc.update("insert into customers(id,site_id,status,email_ciphertext,email_nonce,email_key_version,email_digest,email_digest_key_version,created_at,updated_at) values (?,?,'ACTIVE',decode(repeat('00',16),'hex'),decode(repeat('00',12),'hex'),'test-only',?,'v1',now(),now())",customer.value(),site.value(),"digest-"+customer.value());
        var owner=clocks.admitFresh(billing,site,customer,UUID.randomUUID(),"api-test",1000,Instant.now());
        var step=operations.reserve(billing,ProviderOperation.Kind.TEST_CLOCK_CREATE,owner.intent(),owner.createKey(),owner.createFingerprint());
        var claim=operations.claim(step.id(),"clock",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        var ack=operations.acknowledge(step.id(),"clock",claim.version(),"clock_owned");
        ready=clocks.recordReady(owner,ack,new StripeBillingClockStore.ReadyObservation(StripeBillingClockStore.ReadyState.READY,"clock_owned",1000,2000,3000,Instant.now()));
        step=operations.reserveOwnedBillingCustomer(billing,ready);claim=operations.claim(step.id(),"customer",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        ack=operations.acknowledge(step.id(),"customer",claim.version(),"cus_owned");
        bindings.bindCustomer(billing.id(),site,customer,"cus_owned",ack.id());
        lifecycle();
    }
    void lifecycle() {
        var product=UUID.randomUUID();var price=UUID.randomUUID();subscription=UUID.randomUUID();operation=UUID.randomUUID();
        String key="owned-"+product.toString().substring(0,8);
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,'Owned fixture','ACTIVE',now(),now())",product,site.value(),key);
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?, ?,1,'SUBSCRIPTION',1000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',now(),now(),now())",price,site.value(),product,key);
        jdbc.update("insert into provider_product_mappings(payment_account_id,product_id,provider_product_id,created_at) values (?,?,?,now())",billing.id().value(),product,"prod_"+product.toString().replace("-",""));
        jdbc.update("insert into provider_price_mappings(payment_account_id,price_id,provider_price_id,created_at) values (?,?,?,now())",billing.id().value(),price,"price_"+price.toString().replace("-",""));
        jdbc.update("insert into subscriptions(id,site_id,customer_id,product_id,payment_account_id,currency,billing_price_id,billing_price_key,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,billing_trial_policy,billing_tax_mode,billing_status,created_at,updated_at) values (?,?,?,?,?,'EUR',?,?,1,1000,'MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','INCOMPLETE',now(),now())",subscription,site.value(),customer.value(),product,billing.id().value(),price,key);
        jdbc.update("insert into subscription_operations(id,site_id,customer_id,subscription_id,kind,status,authority_scope,key_digest,fingerprint,provider_operation_key,expected_revision,policy_version,created_at,updated_at) values (?,?,?,?,'CREATE','RESERVED','customer:clock',?,?,?,0,1,now(),now())",operation,site.value(),customer.value(),subscription,"key-"+operation,"v1.paid."+"a".repeat(64),"subscription:"+billing.id().value()+":"+operation+":CREATE");
    }
    OwnedClockSubscription admit(){return owned.admitFresh(billing,ready.owner(),subscription,operation,Instant.now());}
    ProviderOperation reserve(OwnedClockSubscription i){return operations.reserveOwnedBillingSubscription(billing,i);}
    ProviderOperation claim(OwnedClockSubscription i){return operations.claim(reserve(i).id(),"winner",Instant.now(),Duration.ofSeconds(60)).orElseThrow();}
    OwnedClockSubscriptionReceipt ack(OwnedClockSubscription i,ProviderOperation c){return operations.acknowledgeOwnedBillingSubscription(i,c,"sub_owned",4000,"si_owned",900,1100,Instant.now());}
    @Test void exactAdmissionReplayAndKnownReceiptSurviveLocalPublicationWithoutRedispatch() {
        var i=admit();assertThat(admit()).isEqualTo(i);
        var c=claim(i);var r=ack(i,c);
        jdbc.update("update subscription_operations set status='SUCCEEDED',provider_reference='sub_owned',version=version+1 where id=?",operation);
        jdbc.update("update subscriptions set provider_reference='sub_owned',revision=revision+1 where id=?",subscription);
        assertThat(owned.requireCurrent(i)).isEqualTo(i);
        assertThat(owned.requireReceipt(billing.id(),subscription)).isEqualTo(r);
        assertThat(reserve(i).status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(operations.claim(c.id(),"next",Instant.now(),Duration.ofSeconds(60))).isEmpty();
        assertThat(r.providerCreated()).isEqualTo(4000);assertThat(r.periodStart()).isEqualTo(900);
        conflict(()->operations.bindRecovered(operations.findById(c.id()).orElseThrow(),r.reference(),i.fingerprint()));
    }
    @Test void oneClockCannotAdmitAnotherSubscription() {
        admit();lifecycle();assertThatThrownBy(this::admit).isInstanceOf(PaymentApplicationException.class);
        assertThat(count("stripe_billing_owned_subscriptions")).isEqualTo(1);
    }
    @Test void ordinaryReservationAndGenericAckAndRecoveryCannotManufactureReceipt() {
        var i=admit();conflict(()->operations.reserve(billing,ProviderOperation.Kind.SUBSCRIPTION_CREATE,operation,i.operationKey(),i.fingerprint()));
        var c=claim(i);
        assertThatThrownBy(()->operations.acknowledge(c.id(),c.leaseOwner(),c.version(),"sub_owned"))
            .isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->operations.acknowledgeInTransaction(c.id(),c.leaseOwner(),c.version(),"sub_owned")))
            .isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        conflict(()->operations.bindRecovered(c,"sub_owned",i.fingerprint()));
        assertThat(owned.receipt(i)).isEmpty();
        assertThat(operations.findById(c.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.DISPATCHING);
    }
    @Test void unknownAndExpiredClaimsNeverAuthorizeAnotherPost() {
        var i=admit();var c=claim(i);operations.unknown(c.id(),c.leaseOwner(),c.version());
        assertThat(operations.claim(c.id(),"next",Instant.now(),Duration.ofSeconds(60))).isEmpty();
        conflict(()->ack(i,c));
    }
    @Test void sqlCannotResetUnknownAttemptAndEnableAnotherClaim() {
        var i=admit();var c=claim(i);var consumed=operations.unknown(c.id(),c.leaseOwner(),c.version());
        sqlReject(()->jdbc.update("update provider_operations set status='RESERVED',first_dispatched_at=null,retry_before=null,lease_owner=null,lease_expires_at=null where id=?",c.id()));
        sqlReject(()->jdbc.update("update provider_operations set first_dispatched_at=first_dispatched_at+interval '1 second',retry_before=retry_before+interval '1 second' where id=?",c.id()));
        assertThat(operations.findById(c.id())).contains(consumed);
        assertThat(operations.claim(c.id(),"second",Instant.now(),Duration.ofSeconds(60))).isEmpty();
    }
    @Test void originalStepCannotBeDeletedAndReplacedBeforeOrAfterDispatch() {
        var i=admit();var original=reserve(i);
        sqlReject(()->jdbc.update("delete from provider_operations where id=?",original.id()));
        assertThat(reserve(i)).isEqualTo(original);
        var c=operations.claim(original.id(),"first",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        var consumed=operations.unknown(c.id(),c.leaseOwner(),c.version());
        sqlReject(()->jdbc.update("delete from provider_operations where id=?",original.id()));
        assertThat(reserve(i)).isEqualTo(consumed);
        assertThat(operations.claim(original.id(),"second",Instant.now(),Duration.ofSeconds(60))).isEmpty();
    }
    @Test void originalOwnedIdentityCannotMoveOutOfTheSqlGuardBeforeReceiptExists() {
        var i=admit();var c=claim(i);var consumed=operations.unknown(c.id(),c.leaseOwner(),c.version());
        sqlReject(()->jdbc.update("update provider_operations set kind='PAYMENT_PREPARE' where id=?",c.id()));
        sqlReject(()->jdbc.update("update provider_operations set resource_id=? where id=?",UUID.randomUUID(),c.id()));
        sqlReject(()->jdbc.update("update provider_operations set id=? where id=?",UUID.randomUUID(),c.id()));
        sqlReject(()->jdbc.update("update provider_operations set resource_id=?,operation_key='replacement-key',fingerprint='replacement-fingerprint' where id=?",UUID.randomUUID(),c.id()));
        assertThat(operations.findById(c.id())).contains(consumed);
        assertThat(reserve(i)).isEqualTo(consumed);
        assertThat(operations.claim(c.id(),"second",Instant.now(),Duration.ofSeconds(60))).isEmpty();
    }
    @Test void expiredOriginalClaimCannotAckOrReclaim() {
        var i=admit();var step=reserve(i);
        var c=operations.claim(step.id(),"expired",Instant.now().minusSeconds(120),Duration.ofSeconds(60)).orElseThrow();
        conflict(()->ack(i,c));
        assertThat(operations.claim(c.id(),"next",Instant.now(),Duration.ofSeconds(60))).isEmpty();
        assertThat(owned.receipt(i)).isEmpty();
    }
    @Test void stopAfterClaimDeniesAckAndCurrentReadsWithoutErasingAttempt() {
        var i=admit();var c=claim(i);clocks.stop(ready.owner(),StripeBillingClockStore.StopReason.SUBSCRIPTION_CREATE_FAILED,Instant.now());
        conflict(()->ack(i,c));conflict(()->owned.requireCurrent(i));
        assertThat(operations.findById(c.id()).orElseThrow().firstDispatchedAt()).isEqualTo(c.firstDispatchedAt());
    }
    @Test void changedCurrentAccountDeniesAck() {
        var i=admit();var c=claim(i);jdbc.update("update payment_accounts set external_account_id='acct_other' where id=?",billing.id().value());
        conflict(()->ack(i,c));conflict(()->owned.requireCurrent(i));
    }
    @Test void failedReceiptInsertRollsBackOriginalAck() {
        var i=admit();var c=claim(i);
        jdbc.execute("create function owned_test_reject_receipt() returns trigger language plpgsql as $$ begin raise exception 'injected receipt failure' using errcode='23514'; end $$");
        jdbc.execute("create trigger owned_test_reject_receipt before insert on stripe_billing_owned_subscription_receipts for each row execute function owned_test_reject_receipt()");
        try { conflict(()->ack(i,c)); }
        finally {jdbc.execute("drop trigger owned_test_reject_receipt on stripe_billing_owned_subscription_receipts");jdbc.execute("drop function owned_test_reject_receipt()");}
        assertThat(operations.findById(c.id())).contains(c);assertThat(owned.receipt(i)).isEmpty();
        assertThat(ack(i,c).providerOperation()).isEqualTo(c.id());
    }
    @Test void directSqlAckWithoutReceiptCannotCommit() {
        var i=admit();var c=claim(i);
        sqlReject(()->jdbc.update("update provider_operations set status='ACKNOWLEDGED',provider_reference='sub_owned',lease_owner=null,lease_expires_at=null,version=version+1 where id=?",c.id()));
        assertThat(operations.findById(c.id())).contains(c);
    }
    @Test void intentAndReceiptCannotBeChangedOrDeleted() {
        var i=admit();ack(i,claim(i));
        for(String change:List.of("request_fingerprint='v1.paid.'||repeat('b',64)","customer_creation_operation='00000000-0000-0000-0000-000000000001'::uuid","price_reference='price_foreign'","clock_intent='00000000-0000-0000-0000-000000000001'::uuid"))
            sqlReject(()->jdbc.update("update stripe_billing_owned_subscriptions set "+change+" where id=?",i.id()));
        for(String change:List.of("provider_created=4001","reference='sub_foreign'","item_reference='si_foreign'","period_end=1200","provider_operation='00000000-0000-0000-0000-000000000001'::uuid"))
            sqlReject(()->jdbc.update("update stripe_billing_owned_subscription_receipts set "+change+" where intent_id=?",i.id()));
        sqlReject(()->jdbc.update("delete from stripe_billing_owned_subscriptions where id=?",i.id()));
        sqlReject(()->jdbc.update("delete from stripe_billing_owned_subscription_receipts where intent_id=?",i.id()));
    }
    @Test void foreignLifecycleIdsCannotAdmit() {
        conflict(()->owned.admitFresh(billing,ready.owner(),UUID.randomUUID(),operation,Instant.now()));
        conflict(()->owned.admitFresh(billing,ready.owner(),subscription,UUID.randomUUID(),Instant.now()));
        assertThat(count("stripe_billing_owned_subscriptions")).isZero();
    }
    @Test void callerForgedCustomerAckOrTermsNeverConferReservationAuthority() {
        var i=admit();
        var forged=new OwnedClockSubscription(i.id(),i.clock(),i.customerBinding(),UUID.randomUUID(),i.customerReference(),i.subscription(),i.createOperation(),
            i.operationKey(),i.requestFingerprint(),i.expectedRevision(),i.policyVersion(),i.terms(),i.priceReference(),i.productReference(),i.fingerprint(),i.admittedAt());
        conflict(()->operations.reserveOwnedBillingSubscription(billing,forged));
        conflict(()->owned.requireCurrent(forged));
        assertThat(operations.find(billing.id(),i.operationKey())).isEmpty();
    }
    @Test void receiptCannotSpliceAnOriginalCustomerAckOrForeignProviderReference() {
        var i=admit();var c=claim(i);
        String insert="insert into stripe_billing_owned_subscription_receipts(intent_id,payment_account_id,create_operation,provider_operation,reference,provider_created,item_reference,period_start,period_end,acknowledged_at) values (?,?,?,?,?,4000,'si_owned',900,1100,now())";
        sqlReject(()->jdbc.update(insert,i.id(),billing.id().value(),operation,i.customerCreationOperation(),"sub_owned"));
        sqlReject(()->jdbc.update(insert,i.id(),billing.id().value(),operation,c.id(),"sub_foreign"));
        assertThat(owned.receipt(i)).isEmpty();assertThat(operations.findById(c.id())).contains(c);
    }
    @Test void firstClaimRejectsLifecycleThatLostPristineState() {
        var i=admit();var step=reserve(i);jdbc.update("update subscriptions set revision=revision+1 where id=?",subscription);
        conflict(()->operations.claim(step.id(),"winner",Instant.now(),Duration.ofSeconds(60)));
        assertThat(operations.findById(step.id()).orElseThrow().firstDispatchedAt()).isNull();
    }
    @Test void concurrentOriginalClaimsHaveOneWinnerAndLoserDoesNotStopRun() throws Exception {
        var i=admit();var step=reserve(i);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Optional<ProviderOperation>> task=()->{barrier.await(10,TimeUnit.SECONDS);return operations.claim(step.id(),UUID.randomUUID().toString(),Instant.now(),Duration.ofSeconds(60));};
            var a=pool.submit(task);var b=pool.submit(task);
            var results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            var winner=results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(ack(i,winner).reference()).isEqualTo("sub_owned");
        }
    }
    @Test void stopHoldingExclusiveAccountWinsAgainstWaitingAck() throws Exception {
        var i=admit();var c=claim(i);var stopped=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->new TransactionTemplate(transactions).execute(status->{
                jdbc.queryForList("select id from payment_accounts where id=? for update",UUID.class,billing.id().value());
                // Same stop write and owner fence, in a controllable hosted transaction; no nested REQUIRES_NEW call.
                jdbc.update("update stripe_billing_clock_intents set stopped_at=now(),stop_reason='AUTHORITY_REVOKED' where id=?",ready.owner().intent());
                stopped.countDown();try {if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("gate timeout");}
                catch(InterruptedException e){throw new AssertionError(e);}return null;}));
            assertThat(stopped.await(10,TimeUnit.SECONDS)).isTrue();var result=pool.submit(()->ack(i,c));
            try {assertThatThrownBy(()->result.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {release.countDown();}
            holder.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->result.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(PaymentApplicationException.class);
        }
        assertThat(operations.findById(c.id())).contains(c);assertThat(count("stripe_billing_owned_subscription_receipts")).isZero();
    }
    @Test void ackWaitingOnProviderRowMustUseDatabaseClockAfterAcquisition() throws Exception {
        var i=admit();var step=reserve(i);
        // A very short remaining real lease, without mutating the immutable one-use dispatch.
        var c=operations.claim(step.id(),"winner",Instant.now().minusSeconds(59),Duration.ofSeconds(60)).orElseThrow();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->new TransactionTemplate(transactions).execute(status->{
                jdbc.queryForList("select id from provider_operations where id=? for update",UUID.class,c.id());locked.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("gate timeout");}catch(InterruptedException e){throw new AssertionError(e);}return null;}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var result=pool.submit(()->ack(i,c));
            try {assertThatThrownBy(()->result.get(1500,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {release.countDown();}
            holder.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->result.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(PaymentApplicationException.class);
        }
        assertThat(owned.receipt(i)).isEmpty();
    }
    static void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(PaymentApplicationException.class);}
    static void sqlReject(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);}
}
