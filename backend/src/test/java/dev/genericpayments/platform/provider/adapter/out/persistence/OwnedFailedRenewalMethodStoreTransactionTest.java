package dev.genericpayments.platform.provider.adapter.out.persistence;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Explicit socket-free test: no DataSource, context or provider calls. */
class OwnedFailedRenewalMethodStoreTransactionTest {
    @Test void successorDefaultsAndUnconfiguredJdbcDenyBeforeLookingUpAnySource() {
        var port=dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore.class;
        var defaults=(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore)
            java.lang.reflect.Proxy.newProxyInstance(port.getClassLoader(),new Class<?>[]{port},(proxy,method,args)->{
                if(!method.isDefault())throw new AssertionError("unexpected fallback to "+method.getName());
                return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);
            });
        var unconfigured=new JdbcOwnedFailedRenewalMethodStore(new JdbcTemplate());
        var tx=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        try{
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            for(var store:List.of(defaults,unconfigured)){
                assertThatThrownBy(()->store.requireGraceSuccessorEntryInTransaction(null,null,null)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(()->store.reserveBrowserAfterGraceInTransaction(null,null,null,null,null,null,null,null)).isInstanceOf(IllegalStateException.class);
            }
        }finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(tx);}
    }
    @Test void everyStoreEntryRequiresTheCallerTransactionBeforeAnySql() {
        var s=new JdbcOwnedFailedRenewalMethodStore(new JdbcTemplate());
        List<Runnable> calls=List.of(
            ()->s.reserveInTransaction(null,null,null,null,null,null),()->s.requireReservationInTransaction(null),
            ()->s.requirePreparedMethodInTransaction(null),
            ()->s.claimReadInTransaction(null,null,null,null,null,null),()->s.requireReadInTransaction(null,null),
            ()->s.requireMethodContextInTransaction(null,null),()->s.recordCompletionInTransaction(null,null,null,null),
            ()->s.requireCompletionInTransaction(null,null),()->s.associateRecoveryInTransaction(null,null,null,null),
            ()->s.requireRecoveryInTransaction(null,null),()->s.requireRecoveredContextInTransaction(null,null),
            ()->s.requireBrowserRecoveryEnrollmentInTransaction(null,null,null),
            ()->s.requireBrowserRecoveryIdentityInTransaction(null,null),
            ()->s.recordPublicationInTransaction(null,null,null),()->s.publicationInTransaction(null),
            ()->s.requireMethodFactInTransaction(null,null,null,null,null),()->s.requireRecoveryFactInTransaction(null,null,null,null,null));
        for(var call:calls)assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class)
            .hasMessage("Owned method transition requires caller transaction");
    }

}
