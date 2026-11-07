package dev.genericpayments.platform.provider.adapter.out.persistence;

import java.util.List;
import java.lang.reflect.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No DataSource, context, network or provider calls. */
class OwnedMethodBrowserStoreTransactionTest {
    @Test void committedRootCannotWitnessAnAckBeforeItsFirstDispatch() {
        var issued=java.time.Instant.parse("2026-11-07T12:00:00.123456Z");
        var root=new dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.Root(
            java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),2,
            "seti_saved","v1","a".repeat(64),issued,issued.plusSeconds(600));
        for(var first:List.of(issued.minusNanos(1_000),issued)){
            var step=acknowledgedSetup(root,first);
            assertThatCode(()->JdbcOwnedFailedRenewalMethodStore.requireBoundSetupRootChronology(root,step)).doesNotThrowAnyException();
        }
        // Both original dispatch bounds move together, so the existing exact 23h check still passes.
        var contradicted=acknowledgedSetup(root,issued.plusNanos(1_000));
        assertThat(contradicted.retryBefore()).isEqualTo(contradicted.firstDispatchedAt().plusSeconds(23*3600));
        assertThatThrownBy(()->JdbcOwnedFailedRenewalMethodStore.requireBoundSetupRootChronology(root,contradicted))
            .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method transition authority unavailable");
        assertThatThrownBy(()->JdbcOwnedFailedRenewalMethodStore.requireBoundSetupRootChronology(null,acknowledgedSetup(root,issued)))
            .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method transition authority unavailable");
    }
    private static dev.genericpayments.platform.provider.application.model.ProviderOperation acknowledgedSetup(
            dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.Root root,java.time.Instant first){
        return new dev.genericpayments.platform.provider.application.model.ProviderOperation(root.setupStep(),
            new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(java.util.UUID.randomUUID()),"acct_owned","alias",
            dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SETUP_INTENT_CREATE,root.setupOperation(),"original:SETUP","fingerprint",
            dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.ACKNOWLEDGED,root.setupReference(),first,first.plusSeconds(23*3600),null,null,2);
    }
    @Test void preSetupEnrollmentRequiresTheExactOriginalExpiryDespiteJdbcMicrosecondNormalization() {
        var expiry=java.time.Instant.parse("2026-11-07T12:10:00.123456Z");
        var r=mock(dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation.class);
        when(r.id()).thenReturn(java.util.UUID.randomUUID());
        var identity=mock(JdbcOwnedFailedRenewalMethodStore.class);when(identity.requireReservationInTransaction(r)).thenReturn(r);
        var original=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(
            new dev.genericpayments.platform.customers.domain.CustomerSessionId(java.util.UUID.randomUUID()),
            new dev.genericpayments.platform.sites.domain.SiteId(java.util.UUID.randomUUID()),new dev.genericpayments.platform.customers.domain.CustomerId(java.util.UUID.randomUUID()),
            "https://shop.test",java.util.Set.of(dev.genericpayments.platform.customers.domain.CustomerSessionScope.SUBSCRIPTION_MANAGE),expiry);
        var stored=new dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.Enrollment(original.sessionId().value(),original.origin(),expiry);
        var jdbc=new JdbcTemplate(){
            @Override public List<java.util.Map<String,Object>> queryForList(String sql,Object... args){
                assertThat(sql).isEqualTo("select lock_owned_method_browser(?)");assertThat(args).containsExactly(r.id());return List.of();
            }
            @Override public <T>T queryForObject(String sql,Class<T> type,Object... args){
                assertThat(sql).contains("owned_method_initial_operation").contains("clock_timestamp()");
                // Simulates the persisted equality after the adapter normalized the supplied Instant.
                assertThat(args[5]).isEqualTo(java.sql.Timestamp.from(expiry));return type.cast(Boolean.TRUE);
            }
            @Override @SuppressWarnings("unchecked") public <T>List<T> query(String sql,RowMapper<T> mapper,Object... args){
                assertThat(sql).startsWith("select browser_session_id,browser_origin,browser_session_expires_at");return (List<T>)List.of(stored);
            }
        };
        var store=new JdbcOwnedMethodBrowserStore(jdbc,identity);
        var inexact=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(original.sessionId(),original.siteId(),original.customerId(),original.origin(),original.scopes(),expiry.plusNanos(1));
        boolean previous=TransactionSynchronizationManager.isActualTransactionActive();TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(store.setupEnrollment(r,original,expiry.minusSeconds(60))).isEqualTo(stored);
            assertThatThrownBy(()->store.setupEnrollment(r,inexact,expiry.minusSeconds(60)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
        }finally{TransactionSynchronizationManager.setActualTransactionActive(previous);}
    }
    @Test void unextendedStoreCannotAuthorizePreSetupInspection() {
        var store=(OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},
            (proxy,method,args)->{if(!method.isDefault())throw new AssertionError("Unexpected ordinary store call");return InvocationHandler.invokeDefault(proxy,method,args);});
        assertThatThrownBy(()->store.requireBrowserSetupEnrollmentInTransaction(null,null,null))
            .isInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
        assertThatThrownBy(()->store.requireBrowserBoundSetupInTransaction(null,null,null))
            .isInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
    }
    @Test void unextendedStorePortsDenyEveryBrowserEntry() {
        var store=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},
            (proxy,method,args)->{if(!method.isDefault())throw new AssertionError("Unexpected ordinary store call");return InvocationHandler.invokeDefault(proxy,method,args);});
        var browser=List.of("reserveBrowserInTransaction","requireBrowserSetupEnrollmentInTransaction","requireBrowserBoundSetupInTransaction","requireBrowserPendingInTransaction","browserRootInTransaction","insertBrowserRootInTransaction",
            "requireBrowserActionInTransaction","startBrowserSelectionInTransaction","finishBrowserSelectionInTransaction","consumeBrowserFirstInTransaction");
        var methods=java.util.Arrays.stream(OwnedFailedRenewalMethodStore.class.getMethods()).filter(m->browser.contains(m.getName())).toList();
        assertThat(methods).hasSize(10);
        for(var method:methods)assertThatThrownBy(()->{try{method.invoke(store,new Object[method.getParameterCount()]);}catch(InvocationTargetException e){throw e.getCause();}})
            .isInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
    }
    @Test void everyBrowserEntryRequiresCallerTransactionBeforeSql() {
        var s=new JdbcOwnedFailedRenewalMethodStore(new JdbcTemplate());
        List<Runnable> calls=List.of(()->s.reserveBrowserInTransaction(null,null,null,null,null,null,null),
            ()->s.requireBrowserSetupEnrollmentInTransaction(null,null,null),()->s.requireBrowserBoundSetupInTransaction(null,null,null),()->s.requireBrowserPendingInTransaction(null,null,null),()->s.browserRootInTransaction(null),
            ()->s.insertBrowserRootInTransaction(null,null),()->s.requireBrowserActionInTransaction(null,null,null),
            ()->s.startBrowserSelectionInTransaction(null,null,null,null),()->s.finishBrowserSelectionInTransaction(null,null,null,null),
            ()->s.consumeBrowserFirstInTransaction(null,null,null,null,null));
        for(var call:calls)assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class).hasMessage("Owned method transition requires caller transaction");
    }
}
