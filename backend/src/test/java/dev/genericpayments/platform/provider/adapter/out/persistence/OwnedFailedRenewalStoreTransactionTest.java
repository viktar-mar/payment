package dev.genericpayments.platform.provider.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalStore;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No DataSource, Spring context, SQL, mock engine, provider or runtime bootstrap. */
class OwnedFailedRenewalStoreTransactionTest {
    @Test void graceDefaultsDenyBeforeAnyBoundaryCanBeUsed() {
        var store=(OwnedFailedRenewalStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalStore.class},
            (proxy,method,args)->{if(!method.isDefault())throw new AssertionError("Unexpected existing boundary");return InvocationHandler.invokeDefault(proxy,method,args);});
        assertThatThrownBy(()->store.lockGraceLastSecondInTransaction(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->store.prepareGraceLastSecondInTransaction(null,null,null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->store.requireGraceLastSecondInTransaction(null,null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void graceRejectsAnUnrelatedActiveTransactionBeforeIssuingSql() {
        var store=new JdbcOwnedFailedRenewalStore(new JdbcTemplate());var unrelated=new Object();
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.bindResource(unrelated,new Object());
        try {
            for(Runnable entry:List.<Runnable>of(()->store.lockGraceLastSecondInTransaction(null),()->store.prepareGraceLastSecondInTransaction(null,null,null),()->store.requireGraceLastSecondInTransaction(null,null)))
                assertThatThrownBy(entry::run).isInstanceOf(IllegalStateException.class).hasMessage("Owned failed renewal requires caller DataSource transaction");
        } finally {TransactionSynchronizationManager.unbindResource(unrelated);TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void everyPublicOperationRequiresTheCallerTransactionBeforeReadingOrWriting() {
        var store=new JdbcOwnedFailedRenewalStore(new JdbcTemplate());
        List<Runnable> entrypoints=List.of(
            ()->store.admitInTransaction(null,null,null,null),
            ()->store.requireCurrentInTransaction(null),
            ()->store.lockGraceLastSecondInTransaction(null),
            ()->store.prepareGraceLastSecondInTransaction(null,null,null),
            ()->store.requireGraceLastSecondInTransaction(null,null),
            ()->store.claimReadInTransaction(null,null,null,null,null),
            ()->store.requireReadInTransaction(null,null),
            ()->store.recordFailureInTransaction(null,null,null),
            ()->store.requireFailureInTransaction(null),
            ()->store.associateInTransaction(null,null,null,null,null),
            ()->store.requireRecoveryInTransaction(null),
            ()->store.recordPublicationInTransaction(null,null,null),
            ()->store.publicationInTransaction(null),
            ()->store.stopInTransaction(null,null,null),
            ()->store.requireRecoveryFactInTransaction(null,null,null,null,null));
        for(var operation:entrypoints)assertThatThrownBy(operation::run)
            .isInstanceOf(IllegalStateException.class).hasMessage("Owned failed renewal requires caller transaction");
    }
}
