package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.port.out.LoadPaymentAttemptPort.StoredAttempt;
import dev.genericpayments.platform.payments.application.port.out.LoadRefundPort.StoredRefund;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(PaymentPersistenceIntegrationTest.SqlCaptureConfiguration.class)
class PaymentPersistenceIntegrationTest extends PaymentDatabaseFixture {
    static final Queue<String> SQL = new ConcurrentLinkedQueue<>();
    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfiguration {
        @Bean HibernatePropertiesCustomizer capturePaymentSql() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector",
                (StatementInspector) statement -> { SQL.add(statement); return statement; });
        }
    }
    @Autowired LoadPaymentPort loadPayments;
    @Autowired SavePaymentPort savePayments;
    @Autowired LoadPaymentAttemptPort loadAttempts;
    @Autowired SavePaymentAttemptPort saveAttempts;
    @Autowired LoadRefundPort loadRefunds;
    @Autowired SaveRefundPort saveRefunds;
    @Autowired AppendPaymentEventPort appendEvents;
    @Autowired LoadPaymentEventPort loadEvents;
    @Autowired PlatformTransactionManager transactions;

    Payment dynamic() { return Payment.dynamic(new PaymentId(UUID.randomUUID()),new SiteId(site),new PaymentAccountId(account),"order-"+UUID.randomUUID(),Money.of(2000,"EUR"),TaxMode.INCLUSIVE,Map.of("channel","démonstration"),INITIAL_TIME); }
    @Test void paymentRoundTripPreservesEveryDomainFactAndRejectsStaleUpdates() {
        Payment base=dynamic();
        UUID customer=UUID.randomUUID();
        jdbc.update("insert into customers(id,site_id,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())",customer,site);
        Payment original=new Payment(base.id(),base.siteId(),customer,base.paymentAccountId(),base.source(),null,null,base.externalOrderId(),base.money(),base.taxMode(),123L,PaymentStatus.PARTIALLY_REFUNDED,"fake-pi",500,0,INITIAL_TIME,INITIAL_TIME.plusSeconds(1),base.technicalMetadata());
        assertThat(savePayments.save(original)).isEqualTo(original);
        Payment detached=loadPayments.findById(original.siteId(),original.id()).orElseThrow();
        assertThat(detached).isEqualTo(original);
        Payment updated=savePayments.save(detached.refundSucceeded(1000,INITIAL_TIME.plusSeconds(2)));
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.refundedAmountMinor()).isEqualTo(1000);
        assertThatThrownBy(() -> savePayments.save(detached.refundSucceeded(1500,INITIAL_TIME.plusSeconds(3)))).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(loadPayments.findById(new SiteId(UUID.randomUUID()),original.id())).isEmpty();
        assertThat(loadPayments.findBySiteId(new SiteId(UUID.randomUUID()))).isEmpty();
    }
    @Test void catalogSnapshotRoundTripsAndAllowsMultipleNullOrders() {
        UUID product=UUID.randomUUID(), price=UUID.randomUUID();
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'product','Product','ACTIVE',now(),now())",product,site);
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'ticket',2,'ONE_TIME',2000,'EUR','INCLUSIVE','PUBLISHED',now(),now())",price,site,product);
        PriceSnapshot snapshot=new PriceSnapshot(new PriceId(price),"ticket",2,Money.of(2000,"EUR"),TaxMode.INCLUSIVE);
        Payment first=Payment.catalog(new PaymentId(UUID.randomUUID()),new SiteId(site),new PaymentAccountId(account),snapshot,Map.of(),INITIAL_TIME);
        savePayments.save(first);
        savePayments.save(Payment.catalog(new PaymentId(UUID.randomUUID()),new SiteId(site),new PaymentAccountId(account),snapshot,Map.of(),INITIAL_TIME));
        assertThat(loadPayments.findById(first.siteId(),first.id())).contains(first);
        assertThat(loadPayments.findStateById(first.siteId(),first.id()).orElseThrow().returnUrl()).isNull();
    }
    @Test void attemptAndRefundDetachedTokensProtectAllFactsFromStaleSaves() {
        Payment p=savePayments.save(dynamic().succeeded(INITIAL_TIME));
        PaymentAttempt attempt=PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"attempt-op","card",INITIAL_TIME).withProviderIntentReference("fake-intent",INITIAL_TIME).unknown("timeout",INITIAL_TIME);
        StoredAttempt a=saveAttempts.insert(attempt,INITIAL_TIME);
        assertThat(a.version()).isZero();
        assertThat(loadAttempts.findById(p.siteId(),attempt.id())).contains(a);
        StoredAttempt updated=saveAttempts.save(new StoredAttempt(attempt.succeeded(INITIAL_TIME.plusSeconds(1)),a.version()));
        assertThat(updated.version()).isEqualTo(1);
        assertThatThrownBy(() -> saveAttempts.save(new StoredAttempt(attempt.failed("declined",INITIAL_TIME.plusSeconds(2)),a.version()))).isInstanceOf(OptimisticLockingFailureException.class);
        Refund refund=Refund.request(new RefundId(UUID.randomUUID()),p,Money.of(500,"EUR"),"refund-op","requested","operator approved",INITIAL_TIME).withProviderRefundReference("fake-refund",INITIAL_TIME);
        StoredRefund r=saveRefunds.insert(refund);
        assertThat(loadRefunds.findById(p.siteId(),refund.id())).contains(r);
        assertThat(saveRefunds.save(new StoredRefund(refund.processing(INITIAL_TIME.plusSeconds(1)),r.version())).version()).isEqualTo(1);
        assertThatThrownBy(() -> saveRefunds.save(new StoredRefund(refund.failed(INITIAL_TIME.plusSeconds(2)),r.version()))).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(loadAttempts.findById(new SiteId(UUID.randomUUID()),attempt.id())).isEmpty();
        assertThat(loadRefunds.findById(new SiteId(UUID.randomUUID()),refund.id())).isEmpty();
        List<StoredRefund> reserved=new TransactionTemplate(transactions).execute(s -> loadRefunds.findReservedByPaymentForUpdate(p.siteId(),p.id()));
        assertThat(reserved).hasSize(1);
    }
    @Test void journalAtomicallyAllocatesConcurrentSequencesAndRollsBackFailedAppend() throws Exception {
        Payment p=savePayments.save(dynamic());
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Future<PaymentEvent>> futures=new ArrayList<>();
            for(int i=0;i<12;i++) futures.add(pool.submit(() -> appendEvents.append(p.id(),seq -> event(p,seq))));
            Set<Long> sequences=new HashSet<>();
            for(var future:futures) sequences.add(future.get(20,TimeUnit.SECONDS).sequence());
            assertThat(sequences).containsExactlyInAnyOrder(1L,2L,3L,4L,5L,6L,7L,8L,9L,10L,11L,12L);
        }
        assertThatThrownBy(() -> appendEvents.append(p.id(),seq -> {throw new IllegalArgumentException("rejected fact");})).hasRootCauseInstanceOf(IllegalArgumentException.class);
        PaymentEvent fact=appendEvents.append(p.id(),seq -> event(p,seq));
        assertThat(fact.sequence()).isEqualTo(13);
        assertThat(loadEvents.findByPayment(p.siteId(),p.id())).hasSize(13).contains(fact);
        assertThat(loadEvents.findByPayment(new SiteId(UUID.randomUUID()),p.id())).isEmpty();
    }
    PaymentEvent event(Payment p,long seq) { return new PaymentEvent(UUID.randomUUID(),p.id(),seq,p.siteId(),p.paymentAccountId(),PaymentEventType.PAYMENT_PROCESSING,PaymentEventSource.PROVIDER_EVENT,p.money(),"fake-event","fake-object",INITIAL_TIME,INITIAL_TIME.plusSeconds(1),"request-id","idem","a".repeat(64),Map.of("code","processing")); }
    @Test void durableLeaseBlocksCompetitionAndNewAttemptsUntilExpiry() {
        Payment p=savePayments.save(dynamic());
        assertThat(loadPayments.tryAcquireReconciliationLease(p.siteId(),p.id(),"worker-a",INITIAL_TIME,INITIAL_TIME.plusSeconds(30))).isTrue();
        assertThat(loadPayments.tryAcquireReconciliationLease(p.siteId(),p.id(),"worker-b",INITIAL_TIME,INITIAL_TIME.plusSeconds(30))).isFalse();
        PaymentAttempt a=PaymentAttempt.start(new PaymentAttemptId(UUID.randomUUID()),p.id(),1,"leased-op","card",INITIAL_TIME);
        assertThatThrownBy(() -> saveAttempts.insert(a,INITIAL_TIME.plusSeconds(1))).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(loadPayments.tryAcquireReconciliationLease(p.siteId(),p.id(),"worker-b",INITIAL_TIME.plusSeconds(31),INITIAL_TIME.plusSeconds(60))).isTrue();
        assertThat(loadPayments.releaseReconciliationLease(p.siteId(),p.id(),"worker-a")).isFalse();
        assertThat(loadPayments.releaseReconciliationLease(p.siteId(),p.id(),"worker-b")).isTrue();
        assertThat(saveAttempts.insert(a,INITIAL_TIME.plusSeconds(32)).value()).isEqualTo(a);
        Optional<Payment> locked=new TransactionTemplate(transactions).execute(s -> loadPayments.findByIdForUpdate(p.siteId(),p.id()));
        assertThat(locked).isPresent();
    }
    @Test void paymentLockExcludesCompetingClaimsAndExpiresAfterTransaction() throws Exception {
        Payment p=savePayments.save(dynamic());
        CountDownLatch acquired=new CountDownLatch(1), release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<?> holder=pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                loadPayments.findByIdForUpdate(p.siteId(),p.id()).orElseThrow();
                acquired.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("lock release timed out"); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
            }));
            try {
                assertThat(acquired.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(loadPayments.tryAcquireReconciliationLease(p.siteId(),p.id(),"contender",INITIAL_TIME,INITIAL_TIME.plusSeconds(30))).isFalse();
                assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.execute("set local lock_timeout='200ms'");
                    loadPayments.findByIdForUpdate(p.siteId(),p.id());
                })).hasRootCauseInstanceOf(java.sql.SQLException.class)
                    .rootCause().extracting("SQLState").isEqualTo("55P03");
            } finally { release.countDown(); }
            holder.get(10,TimeUnit.SECONDS);
        }
        assertThat(loadPayments.tryAcquireReconciliationLease(p.siteId(),p.id(),"contender",INITIAL_TIME,INITIAL_TIME.plusSeconds(30))).isTrue();
    }
    @Test void malformedMetadataIsRejectedOnReconstitution() {
        Payment p=savePayments.save(dynamic());
        for(String invalid:List.of("{\"nested\":{}}","{\"count\":1}","{\"note\":\"person@example.org\"}","{\"x\":\""+ "v".repeat(501)+"\"}")) {
            jdbc.update("update payments set technical_metadata=?::jsonb where id=?",invalid,p.id().value());
            assertThatThrownBy(() -> loadPayments.findById(p.siteId(),p.id())).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void journalRejectsMismatchedAllocationAndOuterRollbackIsAtomic() {
        Payment p=savePayments.save(dynamic());
        assertThatThrownBy(() -> appendEvents.append(p.id(),seq -> event(p,seq+1))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            savePayments.save(p.processing(INITIAL_TIME));
            appendEvents.append(p.id(),seq -> event(p,seq));
            tx.setRollbackOnly();
        });
        assertThat(loadPayments.findById(p.siteId(),p.id())).contains(p);
        assertThat(loadEvents.findByPayment(p.siteId(),p.id())).isEmpty();
        assertThat(appendEvents.append(p.id(),seq -> event(p,seq)).sequence()).isEqualTo(1);
    }
    @Test void dynamicReturnUrlRoundTripsAndDomainOnlySavesPreserveRoutingMetadata() {
        Payment p=dynamic();
        var state=new LoadPaymentPort.StoredPayment(p,"https://shop.example.test/complete?order=17");
        assertThat(savePayments.save(state)).isEqualTo(state);
        assertThat(loadPayments.findStateById(p.siteId(),p.id())).contains(state);
        Payment changed=savePayments.save(p.processing(INITIAL_TIME.plusSeconds(1)));
        var stored=loadPayments.findStateById(p.siteId(),p.id()).orElseThrow();
        assertThat(stored.value()).isEqualTo(changed);
        assertThat(stored.returnUrl()).isEqualTo(state.returnUrl());
        assertThat(loadPayments.findStateById(new SiteId(UUID.randomUUID()),p.id())).isEmpty();
        assertThatThrownBy(() -> savePayments.save(state)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> new LoadPaymentPort.StoredPayment(p,"x".repeat(2049))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoadPaymentPort.StoredPayment(p,"https://shop.example.test/\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void customerHistoryLoadsEntitiesInOneTenantAndOwnerScopedKeysetQuery() {
        UUID customer = UUID.randomUUID();
        jdbc.update("insert into customers(id,site_id,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)",
            customer, site, Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        jdbc.update("update payments set customer_id=?,created_at=?,updated_at=? where id=?",
            customer, Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), payment);

        SQL.clear();
        List<Payment> history = loadPayments.findCustomerHistory(new SiteId(site), customer,
            INITIAL_TIME.plusSeconds(1), new PaymentId(UUID.randomUUID()), 2);

        assertThat(history).extracting(value -> value.id().value()).containsExactly(payment);
        List<String> selects = SQL.stream().filter(statement -> statement.toLowerCase(Locale.ROOT)
            .contains(" from payments ")).toList();
        assertThat(selects).hasSize(1);
        String normalized = selects.getFirst().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String predicate = normalized.substring(normalized.indexOf(" where "));
        assertThat(predicate).contains(".site_id=?", ".customer_id=?", ".created_at<?", ".created_at=?", ".id<?")
            .contains(" order by ", ".created_at desc", ".id desc", " fetch first ? rows only");
    }
}
