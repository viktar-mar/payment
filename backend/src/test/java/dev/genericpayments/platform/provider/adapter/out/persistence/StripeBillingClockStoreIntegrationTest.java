package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Hosted PostgreSQL/Spring only. Authored and compiled locally; never locally executed. */
@Import(StripeBillingClockStoreIntegrationTest.GateConfiguration.class)
class StripeBillingClockStoreIntegrationTest extends ProviderTestFixture {
    @Autowired StripeBillingClockStore clocks;
    @Autowired ProviderOperationStore operations;
    @Autowired StripeBillingBindingPort bindings;
    @Autowired PlatformTransactionManager transactions;
    @Autowired GateJdbc gate;
    final CustomerId customer=new CustomerId(UUID.randomUUID());
    PaymentAccount billing;
    @BeforeEach void clockFixture() {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_clock',credentials_ref='clock-alias' where id=?",account.id().value());
        billing=PaymentAccount.create(account.id(),account.key(),PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_clock","clock-alias");
        addCustomer(site,customer);
    }
    @AfterEach void releaseGate(){gate.release();}
    @Test void admissionIsIdempotentForExactRunAndRefusesDifferentRunOrRequest() {
        var run=UUID.randomUUID(); var owner=admit(run);
        assertThat(admit(run)).isEqualTo(owner);
        conflict(()->admit(UUID.randomUUID()));
        conflict(()->clocks.admitFresh(billing,site,customer,run,"other-api",1000,Instant.now()));
        conflict(()->ordinary());
        assertThat(count("stripe_billing_clock_intents")).isEqualTo(1);
        assertThat(count("provider_operations")).isZero();
    }
    @Test void everyExistingOrdinaryCustomerStateExcludesAdmission() {
        var op=ordinary(); conflict(()->admit(UUID.randomUUID()));
        var claim=operations.claim(op.id(),"owner",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        var unknown=operations.unknown(op.id(),"owner",claim.version()); conflict(()->admit(UUID.randomUUID()));
        var next=operations.claim(unknown.id(),"next",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        operations.acknowledge(next.id(),"next",next.version(),"cus_ordinary"); conflict(()->admit(UUID.randomUUID()));
        assertThat(count("stripe_billing_clock_intents")).isZero();
    }
    @Test void readyRequiresExactOriginalAckAndFrozenTime() {
        var owner=admit(UUID.randomUUID()); var reserved=clockOperation(owner);
        conflict(()->clocks.recordReady(owner,reserved,observation("clock_owned",1000)));
        var ack=ack(reserved,"clock_owned");
        conflict(()->clocks.recordReady(owner,ack,observation("clock_foreign",1000)));
        assertThatThrownBy(()->clocks.recordReady(owner,ack,observation("clock_owned",1001))).isInstanceOf(IllegalArgumentException.class);
        var observation=observation("clock_owned",1000); var ready=clocks.recordReady(owner,ack,observation);
        assertThat(clocks.recordReady(owner,ack,observation)).isEqualTo(ready);
        assertThat(clocks.ready(owner.intent())).contains(ready);
        assertThat(ready.providerCreated()).isNotEqualTo(ready.frozenTime());
    }
    @Test void intentAndReadyIdentityAndOneWayStopAreProtectedBySql() {
        var ready=ready(); var owner=ready.owner();
        sqlReject(()->jdbc.update("update stripe_billing_clock_intents set run_id=? where id=?",UUID.randomUUID(),owner.intent()));
        sqlReject(()->jdbc.update("delete from stripe_billing_clock_intents where id=?",owner.intent()));
        sqlReject(()->jdbc.update("update stripe_billing_clock_ready set provider_created=provider_created+1 where intent_id=?",owner.intent()));
        sqlReject(()->jdbc.update("delete from stripe_billing_clock_ready where intent_id=?",owner.intent()));
        clocks.stop(owner,StripeBillingClockStore.StopReason.CLOCK_READY_FAILED,Instant.now());
        clocks.stop(owner,StripeBillingClockStore.StopReason.AUTHORITY_REVOKED,Instant.now());
        assertThat(jdbc.queryForObject("select stop_reason from stripe_billing_clock_intents where id=?",String.class,owner.intent())).isEqualTo("CLOCK_READY_FAILED");
        sqlReject(()->jdbc.update("update stripe_billing_clock_intents set stopped_at=null,stop_reason=null where id=?",owner.intent()));
        conflict(()->clocks.requireActive(owner)); conflict(()->operations.reserveOwnedBillingCustomer(billing,ready));
    }
    @Test void databaseRejectsEverySplicedReadyOwnerAndAckDimension() {
        var owner=admit(UUID.randomUUID()); var ack=ack(clockOperation(owner),"clock_owned");
        String insert="insert into stripe_billing_clock_ready(intent_id,payment_account_id,site_id,customer_id,creation_operation_id,reference,frozen_time,provider_created,provider_deletes_after,observed_at,customer_fingerprint) values (?,?,?,?,?,?,?,2000,3000,now(),?)";
        Object[] valid={owner.intent(),billing.id().value(),site.value(),customer.value(),ack.id(),"clock_owned",1000L,"a".repeat(64)};
        for(int dimension:new int[]{0,1,2,3,4,5,6}) {
            var values=valid.clone(); values[dimension]=dimension<5?UUID.randomUUID():(dimension==5?"clock_other":1001L);
            sqlReject(()->jdbc.update(insert,values));
        }
        jdbc.update(insert,valid);
        assertThat(count("stripe_billing_clock_ready")).isEqualTo(1);
    }
    @Test void sqlRejectsClockOperationAgainstForeignIntentAndDuplicateClockReference() {
        var owner=admit(UUID.randomUUID()); var first=ack(clockOperation(owner),"clock_owned");
        sqlReject(()->jdbc.update("update provider_operations set resource_id=? where id=?",UUID.randomUUID(),first.id()));
        var otherCustomer=new CustomerId(UUID.randomUUID()); addCustomer(site,otherCustomer);
        var other=clocks.admitFresh(billing,site,otherCustomer,UUID.randomUUID(),"api-test",1000,Instant.now());
        var second=clockOperation(other); var claim=operations.claim(second.id(),"second",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        conflict(()->operations.acknowledge(second.id(),"second",claim.version(),"clock_owned"));
        assertThat(operations.findById(second.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.DISPATCHING);
    }
    @Test void unknownClockCannotReclaimRecoverOrReserveCustomer() {
        var owner=admit(UUID.randomUUID()); var op=clockOperation(owner);
        var claim=operations.claim(op.id(),"first",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        var unknown=operations.unknown(op.id(),"first",claim.version());
        assertThat(operations.claim(op.id(),"again",Instant.now(),Duration.ofSeconds(60))).isEmpty();
        conflict(()->operations.bindRecovered(unknown,"clock_owned",unknown.fingerprint()));
        conflict(()->operations.reserve(billing,ProviderOperation.Kind.CUSTOMER_CREATE,customer.value(),owner.customerKey(),"ordinary"));
    }
    @Test void expiredClockLeaseConsumesAttemptAndCannotAckLate() {
        var op=clockOperation(admit(UUID.randomUUID()));
        var claim=operations.claim(op.id(),"first",Instant.now().minusSeconds(120),Duration.ofSeconds(60)).orElseThrow();
        assertThat(operations.claim(op.id(),"again",Instant.now(),Duration.ofSeconds(60))).isEmpty();
        conflict(()->operations.acknowledge(op.id(),"first",claim.version(),"clock_late"));
    }
    @Test void knownCustomerAckSurvivesBindingRollbackAndOnlyLocalRepairIsAllowed() {
        var ready=ready(); var op=ack(operations.reserveOwnedBillingCustomer(billing,ready),"cus_clock");
        jdbc.execute("create function clock_test_reject_binding() returns trigger language plpgsql as $$ begin raise exception 'injected local failure' using errcode='23514'; end; $$");
        jdbc.execute("create trigger clock_test_reject_binding before insert on stripe_billing_customers for each row execute function clock_test_reject_binding()");
        try { conflict(()->bindings.bindCustomer(billing.id(),site,customer,"cus_clock",op.id())); }
        finally { jdbc.execute("drop trigger clock_test_reject_binding on stripe_billing_customers");jdbc.execute("drop function clock_test_reject_binding()"); }
        clocks.stop(ready.owner(),StripeBillingClockStore.StopReason.BINDING_FAILED,Instant.now());
        assertThat(bindings.bindCustomer(billing.id(),site,customer,"cus_clock",op.id()).creationOperationId()).isEqualTo(op.id());
        assertThat(operations.claim(op.id(),"again",Instant.now(),Duration.ofSeconds(60))).isEmpty();
        conflict(()->operations.bindRecovered(op,"cus_clock",op.fingerprint()));
        conflict(()->clocks.requireActive(ready.owner()));
        assertThat(count("provider_operations")).isEqualTo(2);
    }
    @Test void otherStoppedReasonDeniesEvenKnownAckLocalBinding() {
        var ready=ready(); var op=ack(operations.reserveOwnedBillingCustomer(billing,ready),"cus_clock");
        clocks.stop(ready.owner(),StripeBillingClockStore.StopReason.AUTHORITY_REVOKED,Instant.now());
        conflict(()->bindings.bindCustomer(billing.id(),site,customer,"cus_clock",op.id()));
    }
    @Test void accountIdentityDriftAfterClaimRefusesAckAndActiveAuthority() {
        var owner=admit(UUID.randomUUID()); var op=clockOperation(owner);
        var claim=operations.claim(op.id(),"first",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        jdbc.update("update payment_accounts set external_account_id='acct_changed' where id=?",billing.id().value());
        conflict(()->operations.acknowledge(op.id(),"first",claim.version(),"clock_owned"));
        conflict(()->clocks.requireActive(owner));
    }
    @Test void concurrentClockClaimsHaveOneWinner() throws Exception {
        assertOneClaim(clockOperation(admit(UUID.randomUUID())));
    }
    @Test void concurrentOwnedReservationsReturnSameReceiptAndOnlyOneClaimWins() throws Exception {
        var ready=ready(); var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<ProviderOperation> reserve=()->{barrier.await(10,TimeUnit.SECONDS);return operations.reserveOwnedBillingCustomer(billing,ready);};
            var first=pool.submit(reserve);var second=pool.submit(reserve);
            var op=first.get(10,TimeUnit.SECONDS); assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo(op);
            assertOneClaim(op);
        }
        assertThat(count("provider_operations")).isEqualTo(2);
    }
    @Test void admissionHoldingCustomerLockWinsAgainstOrdinaryReservation() throws Exception {
        gate.arm("admitter","for update");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var admission=pool.submit(()->named("admitter",()->admit(UUID.randomUUID())));
            assertThat(gate.locked.await(10,TimeUnit.SECONDS)).isTrue();
            var ordinary=pool.submit(()->named("contender",this::ordinary));
            assertThat(gate.contending.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->ordinary.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            gate.release(); admission.get(10,TimeUnit.SECONDS);
            assertConflictFuture(ordinary);
        }
        assertThat(count("provider_operations")).isZero();assertThat(count("stripe_billing_clock_intents")).isEqualTo(1);
    }
    @Test void ordinaryReservationHoldingCustomerLockWinsAgainstAdmission() throws Exception {
        gate.arm("ordinary","for share");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ordinary=pool.submit(()->named("ordinary",this::ordinary));
            assertThat(gate.locked.await(10,TimeUnit.SECONDS)).isTrue();
            var admission=pool.submit(()->named("contender",()->admit(UUID.randomUUID())));
            assertThat(gate.contending.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->admission.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            gate.release();ordinary.get(10,TimeUnit.SECONDS);assertConflictFuture(admission);
        }
        assertThat(count("provider_operations")).isEqualTo(1);assertThat(count("stripe_billing_clock_intents")).isZero();
    }
    @Test void proxiedAdmissionAndReadyAndReservationCommitOutsideRolledBackCallerTransaction() {
        assertThat(AopUtils.isAopProxy(clocks)).isTrue();assertThat(AopUtils.isAopProxy(operations)).isTrue();
        var tx=new TransactionTemplate(transactions);
        var owner=tx.execute(status->{var value=admit(UUID.randomUUID());status.setRollbackOnly();return value;});
        assertThat(clocks.owner(billing.id(),site,customer)).contains(owner);
        var ack=ack(clockOperation(owner),"clock_owned");
        var ready=tx.execute(status->{var value=clocks.recordReady(owner,ack,observation("clock_owned",1000));status.setRollbackOnly();return value;});
        assertThat(clocks.ready(owner.intent())).contains(ready);
        var op=tx.execute(status->{var value=operations.reserveOwnedBillingCustomer(billing,ready);status.setRollbackOnly();return value;});
        assertThat(operations.findById(op.id())).contains(op);
    }
    @Test void stopAfterDispatchRefusesAckAndPreservesEvidence() {
        var owner=admit(UUID.randomUUID());var op=clockOperation(owner);
        var claim=operations.claim(op.id(),"first",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        clocks.stop(owner,StripeBillingClockStore.StopReason.CLOCK_CREATE_FAILED,Instant.now());
        conflict(()->operations.acknowledge(op.id(),"first",claim.version(),"clock_owned"));
        assertThat(operations.findById(op.id()).orElseThrow().firstDispatchedAt()).isEqualTo(claim.firstDispatchedAt());
        assertThat(clocks.ready(owner.intent())).isEmpty();
    }
    @Test void lifecycleReservationWithoutProviderCustomerAlsoExcludesAdmission() {
        localLifecycle();
        conflict(()->admit(UUID.randomUUID()));
        assertThat(count("stripe_billing_clock_intents")).isZero();
        assertThat(count("provider_operations")).isZero();
    }
    @Test void subscriptionAndSetupReservationsAndRawPreexistingClaimsAreFenced() {
        var ready=ready(); ack(operations.reserveOwnedBillingCustomer(billing,ready),"cus_clock");
        var resource=localLifecycle();
        String key="subscription:"+billing.id().value()+":"+resource+":CREATE";
        for(var kind:List.of(ProviderOperation.Kind.SUBSCRIPTION_CREATE,ProviderOperation.Kind.SETUP_INTENT_CREATE)) {
            String exact=key+(kind==ProviderOperation.Kind.SETUP_INTENT_CREATE?":SETUP":"");
            conflict(()->operations.reserve(billing,kind,resource,exact,"facts"));
            var id=UUID.randomUUID();
            jdbc.update("insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint) values (?,?,'acct_clock','clock-alias',?,?,?,'facts')",id,billing.id().value(),kind.name(),resource,exact);
            conflict(()->operations.claim(id,"attempt",Instant.now(),Duration.ofSeconds(60)));
            assertThat(operations.findById(id).orElseThrow().firstDispatchedAt()).isNull();
        }
    }
    @Test void ownedCustomerUnknownCannotAuthorizeAnotherDispatch() {
        var ready=ready();var op=operations.reserveOwnedBillingCustomer(billing,ready);
        var claim=operations.claim(op.id(),"first",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        var unknown=operations.unknown(op.id(),"first",claim.version());
        assertThat(operations.claim(op.id(),"next",Instant.now(),Duration.ofSeconds(60))).isEmpty();
        conflict(()->operations.bindRecovered(unknown,"cus_guess",unknown.fingerprint()));
        // Original timestamps, not a new key, define the consumed attempt after an abandoned lease.
        assertThat(operations.reserveOwnedBillingCustomer(billing,ready)).isEqualTo(unknown);
    }
    @Test void runCannotMoveToAnotherCustomerAndReadyCannotMoveToAnotherRun() {
        var owner=admit(UUID.randomUUID());
        var other=new CustomerId(UUID.randomUUID());addCustomer(site,other);
        conflict(()->clocks.admitFresh(billing,site,other,owner.run(),"api-test",1000,Instant.now()));
        var foreign=clocks.admitFresh(billing,site,other,UUID.randomUUID(),"api-test",1000,Instant.now());
        var ack=ack(clockOperation(owner),"clock_owned");
        conflict(()->clocks.recordReady(foreign,ack,observation("clock_owned",1000)));
    }
    UUID localLifecycle() {
        UUID product=UUID.randomUUID(),price=UUID.randomUUID(),subscription=UUID.randomUUID(),operation=UUID.randomUUID();
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'clock-product','Clock fixture','ACTIVE',now(),now())",product,site.value());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,'clock-price',1,'SUBSCRIPTION',1000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',now(),now(),now())",price,site.value(),product);
        jdbc.update("insert into subscriptions(id,site_id,customer_id,product_id,payment_account_id,currency,billing_price_id,billing_price_key,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,billing_trial_policy,billing_tax_mode,billing_status,created_at,updated_at) values (?,?,?,?,?,'EUR',?,'clock-price',1,1000,'MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','INCOMPLETE',now(),now())",subscription,site.value(),customer.value(),product,billing.id().value(),price);
        String key="subscription:"+billing.id().value()+":"+operation+":CREATE";
        jdbc.update("insert into subscription_operations(id,site_id,customer_id,subscription_id,kind,status,authority_scope,key_digest,fingerprint,provider_operation_key,expected_revision,policy_version,created_at,updated_at) values (?,?,?,?,'CREATE','RESERVED','customer:clock','clock-key',?, ?,0,1,now(),now())",operation,site.value(),customer.value(),subscription,"v1.paid."+"a".repeat(64),key);
        return operation;
    }
    void assertOneClaim(ProviderOperation op)throws Exception {

        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Optional<ProviderOperation>> claim=()->{barrier.await(10,TimeUnit.SECONDS);return operations.claim(op.id(),UUID.randomUUID().toString(),Instant.now(),Duration.ofSeconds(60));};
            var a=pool.submit(claim);var b=pool.submit(claim);
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).stream().filter(Optional::isPresent).count()).isEqualTo(1);
        }
    }
    static <T> T named(String name,Callable<T> action)throws Exception {var thread=Thread.currentThread();var old=thread.getName();try{thread.setName(name);return action.call();}finally{thread.setName(old);}}
    static void assertConflictFuture(Future<?> value) {assertThatThrownBy(()->value.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(PaymentApplicationException.class);}
    ClockOwner admit(UUID run){return clocks.admitFresh(billing,site,customer,run,"api-test",1000,Instant.now());}
    ProviderOperation ordinary(){return operations.reserve(billing,ProviderOperation.Kind.CUSTOMER_CREATE,customer.value(),"ordinary-key","ordinary-fingerprint");}
    ProviderOperation clockOperation(ClockOwner owner){return operations.reserve(billing,ProviderOperation.Kind.TEST_CLOCK_CREATE,owner.intent(),owner.createKey(),owner.createFingerprint());}
    ProviderOperation ack(ProviderOperation op,String reference){var claim=operations.claim(op.id(),"owner",Instant.now(),Duration.ofSeconds(60)).orElseThrow();return operations.acknowledge(op.id(),"owner",claim.version(),reference);}
    ReadyClock ready(){var owner=admit(UUID.randomUUID());var ack=ack(clockOperation(owner),"clock_owned");return clocks.recordReady(owner,ack,observation("clock_owned",1000));}
    StripeBillingClockStore.ReadyObservation observation(String reference,long frozen){return new StripeBillingClockStore.ReadyObservation(StripeBillingClockStore.ReadyState.READY,reference,frozen,2000,3000,Instant.now());}
    void addCustomer(SiteId s,CustomerId c){jdbc.update("insert into customers(id,site_id,status,email_ciphertext,email_nonce,email_key_version,email_digest,email_digest_key_version,created_at,updated_at) values (?,?,'ACTIVE',decode(repeat('00',16),'hex'),decode(repeat('00',12),'hex'),'test-only',?,'v1',now(),now())",c.value(),s.value(),"digest-"+c.value());}
    static void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));}
    static void sqlReject(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);}
    @TestConfiguration(proxyBeanMethods=false) static class GateConfiguration {
        @Bean @Primary GateJdbc gateJdbc(DataSource source){return new GateJdbc(source);}
    }
    /** Blocks only after a real owner lock was acquired in the real Spring receipt transaction. */
    static final class GateJdbc extends JdbcTemplate {
        volatile String holder,suffix;
        volatile CountDownLatch locked=new CountDownLatch(1),contending=new CountDownLatch(1),proceed=new CountDownLatch(0);
        GateJdbc(DataSource source){super(source);}
        void arm(String holder,String suffix){this.holder=holder;this.suffix=suffix;locked=new CountDownLatch(1);contending=new CountDownLatch(1);proceed=new CountDownLatch(1);}
        void release(){proceed.countDown();holder=null;}
        @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
            boolean customerLock=sql.contains("from customers") && (sql.endsWith("for share")||sql.endsWith("for update"));
            if(customerLock && "contender".equals(Thread.currentThread().getName()))contending.countDown();
            var result=super.queryForList(sql,type,args);
            if(customerLock && Thread.currentThread().getName().equals(holder) && sql.endsWith(suffix)) {
                locked.countDown();try{if(!proceed.await(10,TimeUnit.SECONDS))throw new AssertionError("Owner lock gate timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }
            return result;
        }
    }
}
