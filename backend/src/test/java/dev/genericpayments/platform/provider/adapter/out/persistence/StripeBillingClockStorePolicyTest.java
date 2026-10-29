package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;

/** Inert JdbcTemplate double: no DataSource, Mockito, superclass, extensions, sockets or SQL execution. */
class StripeBillingClockStorePolicyTest {
    final Instant now=Instant.parse("2026-10-29T10:00:00Z");
    final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"clock-test",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_test","alias");
    final ClockOwner owner=new ClockOwner(UUID.randomUUID(),UUID.randomUUID(),account.id(),new SiteId(UUID.randomUUID()),new CustomerId(UUID.randomUUID()),"acct_test","alias","api",1000,"name","clock-key","a".repeat(64),now.minusSeconds(1000));
    final ReadyClock ready=new ReadyClock(owner,UUID.randomUUID(),"clock_owned",1000,2000,3000,now.minusSeconds(500),"b".repeat(64));

    @Test void unknownClockCannotBeDispatchedAgainInsideRetryWindow() { refusedRedispatch(ProviderOperation.Kind.TEST_CLOCK_CREATE,ProviderOperation.Status.UNKNOWN); }
    @Test void expiredClockClaimCannotBeReclaimed() { refusedRedispatch(ProviderOperation.Kind.TEST_CLOCK_CREATE,ProviderOperation.Status.DISPATCHING); }
    @Test void unknownOwnedCustomerCannotBeDispatchedAgainInsideRetryWindow() { refusedRedispatch(ProviderOperation.Kind.CUSTOMER_CREATE,ProviderOperation.Status.UNKNOWN); }
    @Test void expiredOwnedCustomerClaimCannotBeReclaimed() { refusedRedispatch(ProviderOperation.Kind.CUSTOMER_CREATE,ProviderOperation.Status.DISPATCHING); }
    void refusedRedispatch(ProviderOperation.Kind kind,ProviderOperation.Status status) {
        var db=new InertJdbc(operation(kind,status),true); var store=new JdbcProviderOperationStore(db);
        assertThat(store.claim(db.operation.id(),"next",now,Duration.ofSeconds(60))).isEmpty();
        assertThat(db.dispatchUpdates).isZero();
    }
    @Test void consumedClockStillRejectsStoppedAuthority() { consumedAuthorityRefused(ProviderOperation.Kind.TEST_CLOCK_CREATE,"stopped"); }
    @Test void consumedCustomerStillRejectsStoppedAuthority() { consumedAuthorityRefused(ProviderOperation.Kind.CUSTOMER_CREATE,"stopped"); }
    @Test void consumedClockStillRejectsChangedPinnedAccount() { consumedAuthorityRefused(ProviderOperation.Kind.TEST_CLOCK_CREATE,"account"); }
    @Test void consumedCustomerStillRejectsChangedPinnedAccount() { consumedAuthorityRefused(ProviderOperation.Kind.CUSTOMER_CREATE,"account"); }
    @Test void consumedClockStillRejectsForeignOriginalIdentity() { consumedAuthorityRefused(ProviderOperation.Kind.TEST_CLOCK_CREATE,"identity"); }
    @Test void consumedCustomerStillRejectsForeignOriginalIdentity() { consumedAuthorityRefused(ProviderOperation.Kind.CUSTOMER_CREATE,"identity"); }
    private void consumedAuthorityRefused(ProviderOperation.Kind kind,String change) {
        for(var status:List.of(ProviderOperation.Status.UNKNOWN,ProviderOperation.Status.DISPATCHING)) {
            var step=operation(kind,status);
            if(change.equals("identity"))step=new ProviderOperation(step.id(),step.accountId(),step.externalAccountId(),step.credentialsRef(),step.kind(),step.resourceId(),
                step.operationKey(),"foreign-fingerprint",step.status(),step.providerReference(),step.firstDispatchedAt(),step.retryBefore(),step.leaseOwner(),step.leaseExpiresAt(),step.version());
            var db=new InertJdbc(step,true);db.stopped=change.equals("stopped");db.pinnedAccountChanged=change.equals("account");
            assertThatThrownBy(()->new JdbcProviderOperationStore(db).claim(db.operation.id(),"next",now,Duration.ofSeconds(60)))
                .isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
            assertThat(db.dispatchUpdates).isZero();assertThat(db.inserts).isZero();
        }
    }
    @Test void liveClockAndCustomerClaimLoserDoesNotWriteOrInvalidateWinner() {
        for(var kind:List.of(ProviderOperation.Kind.TEST_CLOCK_CREATE,ProviderOperation.Kind.CUSTOMER_CREATE)) {
            var old=operation(kind,ProviderOperation.Status.DISPATCHING);
            var live=new ProviderOperation(old.id(),old.accountId(),old.externalAccountId(),old.credentialsRef(),old.kind(),old.resourceId(),old.operationKey(),old.fingerprint(),
                old.status(),null,now.minusSeconds(10),now.plusSeconds(82790),"winner",now.plusSeconds(50),old.version());
            var db=new InertJdbc(live,true);
            assertThat(new JdbcProviderOperationStore(db).claim(live.id(),"loser",now,Duration.ofSeconds(60))).isEmpty();
            assertThat(db.operation).isEqualTo(live);assertThat(db.dispatchUpdates).isZero();assertThat(db.inserts).isZero();
        }
    }
    @Test void ordinaryCustomerStillReclaimsItsOriginalKey() {
        var db=new InertJdbc(operation(ProviderOperation.Kind.CUSTOMER_CREATE,ProviderOperation.Status.UNKNOWN),false);
        assertThat(new JdbcProviderOperationStore(db).claim(db.operation.id(),"next",now,Duration.ofSeconds(60))).isPresent();
        assertThat(db.dispatchUpdates).isEqualTo(1);
    }
    @Test void firstClockClaimRemainsAvailable() {
        var db=new InertJdbc(operation(ProviderOperation.Kind.TEST_CLOCK_CREATE,ProviderOperation.Status.RESERVED),true);
        assertThat(new JdbcProviderOperationStore(db).claim(db.operation.id(),"first",now,Duration.ofSeconds(60))).isPresent();
        assertThat(db.dispatchUpdates).isEqualTo(1);
    }
    @Test void ordinaryReserveCannotSelectTheOwnedCustomerLaneEvenWithItsExactFingerprint() {
        var db=new InertJdbc(operation(ProviderOperation.Kind.CUSTOMER_CREATE,ProviderOperation.Status.RESERVED),true);
        assertThatThrownBy(()->new JdbcProviderOperationStore(db).reserve(account,ProviderOperation.Kind.CUSTOMER_CREATE,
            owner.customer().value(),owner.customerKey(),ready.customerFingerprint())).isInstanceOf(PaymentApplicationException.class);
        assertThat(db.inserts).isZero();
    }
    @Test void explicitOwnedReservationRequiresTheExactPersistedReadyReceipt() {
        var db=new InertJdbc(operation(ProviderOperation.Kind.CUSTOMER_CREATE,ProviderOperation.Status.RESERVED),true);
        var forged=new ReadyClock(owner,ready.creationOperation(),"clock_other",1000,2000,3000,ready.observedAt(),ready.customerFingerprint());
        assertThatThrownBy(()->new JdbcProviderOperationStore(db).reserveOwnedBillingCustomer(account,forged)).isInstanceOf(PaymentApplicationException.class);
        assertThat(db.inserts).isZero();
    }
    @Test void ownedClockAndCustomerRejectManualRecoveryIncludingKnownAcks() {
        for(var kind:List.of(ProviderOperation.Kind.TEST_CLOCK_CREATE,ProviderOperation.Kind.CUSTOMER_CREATE)) {
            var db=new InertJdbc(operation(kind,ProviderOperation.Status.ACKNOWLEDGED),true);
            assertThatThrownBy(()->new JdbcProviderOperationStore(db).bindRecovered(db.operation,db.operation.providerReference(),db.operation.fingerprint())).isInstanceOf(PaymentApplicationException.class);
            assertThat(db.dispatchUpdates).isZero();
        }
    }
    @Test void stoppedRunCannotClaimOrAcknowledge() {
        var db=new InertJdbc(operation(ProviderOperation.Kind.TEST_CLOCK_CREATE,ProviderOperation.Status.RESERVED),true); db.stopped=true;
        assertThatThrownBy(()->new JdbcProviderOperationStore(db).claim(db.operation.id(),"first",now,Duration.ofSeconds(60))).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->new JdbcProviderOperationStore(db).acknowledge(db.operation.id(),"first",0,"clock_owned")).isInstanceOf(PaymentApplicationException.class);
        assertThat(db.dispatchUpdates).isZero();
    }
    @Test void clockAckRefusesWrongReferenceShapeBeforeWrite() {
        var db=new InertJdbc(operation(ProviderOperation.Kind.TEST_CLOCK_CREATE,ProviderOperation.Status.DISPATCHING),true);
        assertThatThrownBy(()->new JdbcProviderOperationStore(db).acknowledge(db.operation.id(),"first",0,"cus_foreign")).isInstanceOf(PaymentApplicationException.class);
        assertThat(db.dispatchUpdates).isZero();
    }
    @Test void knownAckBindingRepairAllowsOnlyTheBindingFailureStop() {
        var db=new InertJdbc(operation(ProviderOperation.Kind.CUSTOMER_CREATE,ProviderOperation.Status.ACKNOWLEDGED),true);
        db.stopped=true; db.bindingFailure=true;
        assertThat(new JdbcProviderOperationStore(db).lockRecovery(db.operation)).isEqualTo(db.operation);
        db.bindingFailure=false;
        assertThatThrownBy(()->new JdbcProviderOperationStore(db).lockRecovery(db.operation)).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void admissionCannotCaptureAnExistingLocalSubscriptionWithoutCustomerReceipt() {
        var inserts=new java.util.concurrent.atomic.AtomicInteger();
        var db=new JdbcTemplate() {
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){return List.of();}
            @Override @SuppressWarnings("unchecked") public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
                if(sql.contains("from stripe_billing_customers")||sql.contains("from provider_operations"))return List.of();
                return (List<T>)List.of(UUID.randomUUID());
            }
            @Override public int update(String sql,Object... args){inserts.incrementAndGet();return 1;}
        };
        var store=new JdbcStripeBillingClockStore(db,new dev.genericpayments.platform.shared.adapter.out.security.Sha256DigestAdapter());
        assertThatThrownBy(()->store.admitFresh(account,owner.site(),owner.customer(),UUID.randomUUID(),"api",1000,now))
            .isInstanceOf(PaymentApplicationException.class);
        assertThat(inserts.get()).isZero();
    }
    ProviderOperation operation(ProviderOperation.Kind kind,ProviderOperation.Status status) {

        boolean clock=kind==ProviderOperation.Kind.TEST_CLOCK_CREATE, reserved=status==ProviderOperation.Status.RESERVED;
        return new ProviderOperation(UUID.randomUUID(),account.id(),"acct_test","alias",kind,clock?owner.intent():owner.customer().value(),
            clock?owner.createKey():owner.customerKey(),clock?owner.createFingerprint():ready.customerFingerprint(),status,
            status==ProviderOperation.Status.ACKNOWLEDGED?(clock?"clock_owned":"cus_owned"):null,
            reserved?null:now.minusSeconds(120),reserved?null:now.plusSeconds(23*3600-120),
            status==ProviderOperation.Status.DISPATCHING?"first":null,status==ProviderOperation.Status.DISPATCHING?now.minusSeconds(60):null,0);
    }
    final class InertJdbc extends JdbcTemplate {
        final ProviderOperation operation; final boolean owned; boolean stopped,bindingFailure,pinnedAccountChanged; int dispatchUpdates,inserts;
        InertJdbc(ProviderOperation operation,boolean owned){this.operation=operation;this.owned=owned;}
        @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            if(sql.contains("update provider_operations")){dispatchUpdates++; return (List<T>)List.of(operation);}
            if(sql.contains("from provider_operations"))return (List<T>)List.of(operation);
            if(sql.contains("from stripe_billing_clock_intents"))return owned?(List<T>)List.of(owner):List.of();
            if(sql.contains("from stripe_billing_clock_ready"))return owned?(List<T>)List.of(ready):List.of();
            throw new AssertionError("Unexpected SQL template: "+sql);
        }
        @Override @SuppressWarnings("unchecked") public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
            if(sql.contains("from payment_accounts") && pinnedAccountChanged)return List.of();
            if(sql.contains("select site_id from customers"))return (List<T>)List.of(owner.site().value());
            if(sql.contains("from stripe_billing_clock_intents")) {
                if(!owned || (sql.contains("stopped_at is null") && stopped && !(sql.contains("BINDING_FAILED")&&bindingFailure)))return List.of();
                return (List<T>)List.of(owner.intent());
            }
            return (List<T>)List.of(operation.id());
        }
        @Override public int update(String sql,Object... args){inserts++;return 1;}
    }
}
