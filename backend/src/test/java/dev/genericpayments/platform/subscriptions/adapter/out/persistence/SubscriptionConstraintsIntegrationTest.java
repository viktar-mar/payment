package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;

class SubscriptionConstraintsIntegrationTest extends SubscriptionLedgerFixture {
    @Test void reconciliationStartsAtApplicationTime() {
        assertThat(jdbc.queryForObject("select now()",Timestamp.class).toInstant()).isNotEqualTo(INITIAL_TIME);
        var s=store.save(subscription(),-1);
        assertThat(jdbc.queryForObject("select next_reconcile_at from subscriptions where id=?",Timestamp.class,s.id()).toInstant()).isEqualTo(INITIAL_TIME);
        assertThat(jdbc.queryForObject("select next_reconcile_at<=? from subscriptions where id=?",Boolean.class,Timestamp.from(INITIAL_TIME),s.id())).isTrue();
    }
    @Test void reconciliationScheduleSurvivesOrdinaryCasUpdate() {
        var s=store.save(subscription(),-1);
        jdbc.update("""
            update subscriptions set next_reconcile_at=?,reconcile_lease_owner='worker',reconcile_lease_token=?,
              reconcile_lease_until=?,reconcile_lease_revision=0,reconcile_attempts=2 where id=?
            """,Timestamp.from(INITIAL_TIME.plusSeconds(600)),UUID.randomUUID(),Timestamp.from(INITIAL_TIME.plusSeconds(60)),s.id());
        String schedule="select next_reconcile_at,reconcile_lease_owner,reconcile_lease_token,reconcile_lease_until,reconcile_lease_revision,reconcile_attempts from subscriptions where id=?";
        var before=jdbc.queryForMap(schedule,s.id());
        store.save(s.pause(INITIAL_TIME.plusSeconds(1)),0);
        assertThat(jdbc.queryForMap(schedule,s.id())).isEqualTo(before);
    }
    @Test void ledgerHasDurableTablesForFinancialFactsAndFutureConsumers() {
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema=current_schema()",String.class))
            .contains("subscriptions","subscription_operations","subscription_trial_eligibility","subscription_billing_records",
                "subscription_provider_objects","subscription_events","subscription_event_sequences","subscription_quotes",
                "subscription_action_capabilities","subscription_portal_sessions");
    }
    @Test void optimisticWritesAndScheduledCancellationKeepTheProductSlot() {
        var s=store.save(subscription(),-1);
        assertThat(store.find(site,s.id())).contains(s);
        assertThatThrownBy(()->store.save(s,-1)).isInstanceOf(RuntimeException.class);
        var scheduled=s.scheduleCancellation(INITIAL_TIME.plusSeconds(200),INITIAL_TIME);
        store.save(scheduled,0);
        assertThatThrownBy(()->store.save(s.pause(INITIAL_TIME),0)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThatThrownBy(()->store.save(subscription(),-1)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(store.hasBlockingCustomerSubscriptions(site,customer.id())).isTrue();
        store.save(scheduled.cancel("CONFIRMED",INITIAL_TIME),1);
        assertThat(store.save(subscription(),-1).id()).isNotEqualTo(s.id());
    }
    @Test void concurrentInsertsHaveOneWinnerWhileDifferentProductsAreAllowed() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Callable<Boolean> create=()->{start.await();try {store.save(subscription(),-1);return true;}catch(org.springframework.dao.DataIntegrityViolationException failure){return false;}};
            var a=pool.submit(create);var b=pool.submit(create);start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        terms=price(new ProductId(UUID.randomUUID()));
        store.save(subscription(),-1);
        assertThat(jdbc.queryForObject("select count(*) from subscriptions",Integer.class)).isEqualTo(2);
    }
    @Test void databaseRejectsOwnershipPartialPeriodsAndAccountInvoiceMismatch() {
        var s=store.save(subscription(),-1);
        assertThatThrownBy(()->jdbc.update("update subscriptions set current_period_start=? where id=?",Timestamp.from(INITIAL_TIME),s.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update subscriptions set customer_id=? where id=?",UUID.randomUUID(),s.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var other=price(new ProductId(UUID.randomUUID()));
        assertThatThrownBy(()->jdbc.update("update subscriptions set billing_price_id=? where id=?",other.priceId().value(),s.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID accountB=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'other-account','FAKE','SANDBOX','ACTIVE','fake','ref',?,0)",accountB,Timestamp.from(INITIAL_TIME));
        jdbc.update("update sites set payment_account_id=? where id=?",accountB,site.value());
        assertThat(store.find(site,s.id()).orElseThrow().paymentAccountId()).isEqualTo(account.id());
        var bill=bill(s,0,BillingRecord.Settlement.ZERO_AMOUNT);
        bills.upsert(bill);
        assertThatThrownBy(()->jdbc.update("update subscription_billing_records set payment_account_id=? where id=?",accountB,bill.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void trialReservationIsAtomicAndCannotBeReleasedAfterConsumption() throws Exception {
        var a=operation(null,SubscriptionOperation.Kind.CREATE,"trial-a");
        var b=operation(null,SubscriptionOperation.Kind.CREATE,"trial-b");
        tx.required(()->operations.claim(a));tx.required(()->operations.claim(b));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var one=pool.submit(()->{start.await();return trials.reserve(site,customer.id(),terms.productId(),a.id());});
            var two=pool.submit(()->{start.await();return trials.reserve(site,customer.id(),terms.productId(),b.id());});
            start.countDown();assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        UUID winner=jdbc.queryForObject("select reserved_by_operation from subscription_trial_eligibility",UUID.class);
        trials.consume(winner,INITIAL_TIME);trials.releaseConfirmedFailure(winner);
        assertThat(trials.isEligible(site,customer.id(),terms.productId())).isFalse();
    }
    @Test void operationClaimsReplayAndSerializeMutationsButAllowReconciliationAndPortal() {
        var s=store.save(subscription(),-1);var op=operation(s.id(),SubscriptionOperation.Kind.CANCEL,"same-key");
        assertThat(tx.required(()->operations.claim(op)).owner()).isTrue();
        assertThat(tx.required(()->operations.claim(operation(s.id(),SubscriptionOperation.Kind.CANCEL,"same-key"))).operation().id()).isEqualTo(op.id());
        assertThatThrownBy(()->tx.required(()->operations.claim(operation(s.id(),SubscriptionOperation.Kind.CHANGE,"other-key")))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        tx.required(()->operations.claim(operation(s.id(),SubscriptionOperation.Kind.RECONCILE,"reconcile")));
        tx.required(()->operations.claim(operation(s.id(),SubscriptionOperation.Kind.PORTAL,"portal")));
        assertThat(operations.hasUnresolved(site,s.id())).isTrue();
        var done=new SubscriptionOperation(op.id(),op.siteId(),op.customerId(),op.subscriptionId(),op.kind(),SubscriptionOperation.Status.SUCCEEDED,op.authorityScope(),op.keyDigest(),op.fingerprint(),op.providerOperationKey(),op.expectedRevision(),op.policyVersion(),null,null,op.createdAt(),op.updatedAt(),1);
        operations.save(done,0);
        assertThatThrownBy(()->operations.save(done,0)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(operations.hasUnresolved(site,s.id())).isFalse();
    }
    @Test void confirmedFutureCashSurvivesDuplicateOpenUpsertWithoutGrantingAccess() {
        var s=store.save(subscription(),-1);var cash=bill(s,100,BillingRecord.Settlement.CONFIRMED_CASH);
        bills.upsert(cash);
        var stale=new BillingRecord(cash.id(),site,s.id(),account.id(),cash.providerInvoiceReference(),cash.kind(),terms,null,cash.period(),100,"EUR",BillingRecord.Settlement.OPEN,0,INITIAL_TIME,INITIAL_TIME);
        assertThat(bills.upsert(stale).settlement()).isEqualTo(BillingRecord.Settlement.CONFIRMED_CASH);
        assertThat(store.find(site,s.id()).orElseThrow().billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);
        assertThat(jdbc.queryForObject("select count(*) from payments",Integer.class)).isZero();
    }
    @Test void partialPendingChangeMustNotPassSqlNullCheck() {
        var s=store.save(subscription(),-1);var op=operation(s.id(),SubscriptionOperation.Kind.CHANGE,"pending");tx.required(()->operations.claim(op));
        assertThatThrownBy(()->jdbc.update("""
            update subscriptions set pending_operation_id=?,pending_effective_at=?,pending_price_id=billing_price_id,
            pending_price_key=billing_price_key,pending_price_version=billing_price_version,pending_amount_minor=billing_amount_minor,
            pending_interval=billing_interval,pending_trial_days=billing_trial_days,pending_trial_policy=billing_trial_policy,pending_tax_mode=billing_tax_mode where id=?
            """,op.id(),Timestamp.from(INITIAL_TIME),s.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void quoteCannotBindPricesFromAnotherProduct() {
        var s=store.save(subscription(),-1);var other=price(new ProductId(UUID.randomUUID()));
        assertThatThrownBy(()->jdbc.update("""
            insert into subscription_quotes(id,site_id,customer_id,subscription_id,product_id,authority_scope,from_price_id,from_price_version,
              currency,target_price_id,target_price_key,target_price_version,target_amount_minor,target_interval,target_trial_days,target_trial_policy,target_tax_mode,
              timing,due_now_minor,next_amount_minor,effective_at,expires_at,created_at,tax_final,subscription_revision,policy_version,provider_quote_reference)
            values (?,?,?,?,?,'digest',?,1,'EUR',?,?,1,2000,'MONTH',3,'OPTIONAL_DURING_TRIAL','NONE','IMMEDIATE',100,2000,?,?,?,false,0,0,'quote-ref')
            """,UUID.randomUUID(),site.value(),customer.id().value(),s.id(),other.productId().value(),other.priceId().value(),other.priceId().value(),other.priceKey(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME.plusSeconds(60)),Timestamp.from(INITIAL_TIME)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void debtPeriodAndNullableFixedDeadlineRoundTripWithoutRecomputation() {
        var s=store.save(subscription(),-1);var bill=bills.upsert(bill(s,100,BillingRecord.Settlement.OPEN));
        var failed=s.renewalFailed(bill.id(),bill.period(),3,INITIAL_TIME);
        assertThat(store.save(failed,0).debt()).isEqualTo(new Subscription.Debt(bill.id(),bill.period(),null));
        assertThat(store.find(site,s.id()).orElseThrow().debt().graceDeadline()).isNull();
    }
    @Test void transactionAndAccountLocksRequireAmbientTransaction() {
        assertThat(tx.active()).isFalse();
        assertThatThrownBy(()->store.lock(site,UUID.randomUUID())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var accountLocks=applicationContext.getBean(dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort.class);
        assertThatThrownBy(()->accountLocks.findByIdForUpdate(account.id())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(tx.required(()->accountLocks.findByIdForUpdate(account.id())).orElseThrow().id()).isEqualTo(account.id());
        var op=operation(null,SubscriptionOperation.Kind.CREATE,"independent");
        assertThatThrownBy(()->tx.required(()->{assertThat(tx.active()).isTrue();tx.requiresNew(()->operations.claim(op));throw new IllegalStateException("outer rollback");})).hasMessage("outer rollback");
        assertThat(tx.required(()->operations.lock(op.id())).id()).isEqualTo(op.id());
    }
    @Autowired org.springframework.context.ApplicationContext applicationContext;
}

abstract class SubscriptionLedgerFixture extends ProviderTestFixture {
    @Autowired SubscriptionStore store;
    @Autowired BillingRecordStore bills;
    @Autowired SubscriptionOperationStore operations;
    @Autowired TrialEligibilityStore trials;
    @Autowired SubscriptionTransactionPort tx;
    @Autowired CustomerStore customers;
    SubscriptionPriceTerms terms;
    Customer customer;
    @BeforeEach void ledgerFixture() {
        customer=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        terms=price(new ProductId(UUID.randomUUID()));
    }
    SubscriptionPriceTerms price(ProductId product) {
        UUID id=UUID.randomUUID();String key="price-"+id;
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,'Plan','ACTIVE',?,?)",product.value(),site.value(),"product-"+product.value(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',2000,'EUR','MONTH',3,'NONE','PUBLISHED',?,?,?)",id,site.value(),product.value(),key,Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        return new SubscriptionPriceTerms(new PriceId(id),key,1,product,Money.of(2000,"EUR"),BillingInterval.MONTH,3,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
    }
    Subscription subscription() {return new Subscription(UUID.randomUUID(),site,customer.id(),terms.productId(),account.id(),terms,null,Subscription.Status.INCOMPLETE,null,null,null,null,null,null,null,null,0,INITIAL_TIME,INITIAL_TIME,null);}
    SubscriptionOperation operation(UUID subscription,SubscriptionOperation.Kind kind,String key) {
        UUID id=UUID.randomUUID();return new SubscriptionOperation(id,site,customer.id(),subscription,kind,SubscriptionOperation.Status.RESERVED,"authority-digest",key,"safe-fingerprint","operation-"+id,0,0,null,null,INITIAL_TIME,INITIAL_TIME,0);
    }
    BillingRecord bill(Subscription s,long amount,BillingRecord.Settlement settlement) {return new BillingRecord(UUID.randomUUID(),site,s.id(),account.id(),"invoice-"+UUID.randomUUID(),BillingRecord.Kind.INITIAL,terms,null,new Subscription.Period(INITIAL_TIME.plusSeconds(100),INITIAL_TIME.plusSeconds(300)),amount,"EUR",settlement,0,INITIAL_TIME,INITIAL_TIME);}
}
