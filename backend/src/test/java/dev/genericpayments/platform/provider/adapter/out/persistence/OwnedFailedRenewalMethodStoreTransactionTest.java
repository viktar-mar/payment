package dev.genericpayments.platform.provider.adapter.out.persistence;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Explicit socket-free test: no DataSource, context or provider calls. */
class OwnedFailedRenewalMethodStoreTransactionTest {
    @Test void typedTerminalPortsFailClosedBeforeAnySqlOrCallerWork(){
        var type=dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore.class;
        var older=(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore)
            java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->{
                if(!m.isDefault())throw new AssertionError("legacy fallback");return java.lang.reflect.InvocationHandler.invokeDefault(proxy,m,args);
            });
        var unconfigured=new JdbcOwnedFailedRenewalMethodStore(new JdbcTemplate());
        var owner=dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.SuccessorOwner.create();
        var reason=dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Reason.UNCERTAIN;
        for(var store:List.of(older,unconfigured))
            for(Runnable call:List.<Runnable>of(()->store.terminateSuccessorWriteInTransaction(null,null,owner),
                    ()->store.terminateSuccessorReadInTransaction(null,null,reason,owner),
                    ()->store.terminateSuccessorConfirmationInTransaction(null,null,null,owner)))
                assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class);
    }
    @Test void capturedFactCallerAuthenticatesConfigurationAndStillRejectsReboundPhysicalHolder(){
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");
        var foreign=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();
        try{
            var caller=OwnedGraceCallerConnection.capture(h.jdbc,h.factory,"wrong fact caller");
            assertThat(caller.requireReadCommitted(h.jdbc,h.factory)).isSameAs(h.em);
            assertThatThrownBy(()->caller.requireReadCommitted(foreign.jdbc,h.factory)).hasMessage("wrong fact caller");
            assertThatThrownBy(()->caller.requireReadCommitted(h.jdbc,foreign.factory)).hasMessage("wrong fact caller");
            assertThatThrownBy(()->caller.requireReadCommitted(h.jdbc,null)).hasMessage("wrong fact caller");
            var holder=org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(h.dataSource);
            try{
                org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(h.dataSource,new org.springframework.jdbc.datasource.ConnectionHolder(h.physical));
                assertThatThrownBy(()->caller.requireReadCommitted(h.jdbc,h.factory)).hasMessage("wrong fact caller");
            }finally{
                org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(h.dataSource);
                org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(h.dataSource,holder);
            }
            assertThat(h.queries).hasValue(0);assertThat(foreign.queries).hasValue(0);
        }finally{h.unbind();}
    }
    @Test void olderPortsCannotApplySuccessorFactsOrConsumeTaggedReadReceipts() {
        var type=dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore.class;
        var older=(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore)
            java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->{
                if(!m.isDefault())throw new AssertionError("legacy fallback");return java.lang.reflect.InvocationHandler.invokeDefault(proxy,m,args);
            });
        var owner=dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.SuccessorOwner.create();
        for(Runnable call:List.<Runnable>of(()->older.claimReadInTransaction(null,null,null,null,null,null,owner),
            ()->older.requireReadInTransaction(null,null,owner),()->older.requireMethodContextInTransaction(null,null,owner),
            ()->older.requireRecoveredContextInTransaction(null,null,owner),()->older.recordCompletionInTransaction(null,null,null,null,owner),
            ()->older.recordPublicationInTransaction(null,null,null,owner),
            ()->older.withSuccessorFactInTransaction(null,null,owner,()->{throw new AssertionError("unauthorized callback");})))
            assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class);
    }
    @Test void configuredHandoffEntriesRejectForeignFactoryPhysicalMismatchAndNonReadCommittedBeforeSql() {
        for(String mode:List.of("foreignFactory","foreignConnection","repeatableRead")){
            var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary(mode);h.bind();
            try{
                var factory=mode.equals("foreignFactory")?new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid").factory:h.factory;
                var store=new JdbcOwnedFailedRenewalMethodStore(h.jdbc,factory);
                for(var call:List.<Runnable>of(()->store.requireGraceSuccessorEntryInTransaction(null,null,null),
                        ()->store.reserveBrowserAfterGraceInTransaction(null,null,null,null,null,null,null,null)))
                    assertThatThrownBy(call::run).as(mode).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Owned grace successor requires original caller");
                assertThat(h.queries).hasValue(0);
            }finally{h.unbind();}
        }
    }
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
