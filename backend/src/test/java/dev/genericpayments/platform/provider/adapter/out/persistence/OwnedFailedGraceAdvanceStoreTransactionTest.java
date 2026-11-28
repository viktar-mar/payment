package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.port.out.OwnedFailedGraceAdvanceStore;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.InvocationBudget;
import dev.genericpayments.platform.provider.application.service.OwnedFailedGraceAdvanceInertFixture;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/** No SQL engine or actual connection. Reuses the V38 physical-connection negative fixture. */
class OwnedFailedGraceAdvanceStoreTransactionTest {
    @Test void everyDefaultIsClosedWithoutFallingBackToOrdinaryProviderOperations(){
        var store=(OwnedFailedGraceAdvanceStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedGraceAdvanceStore.class},(proxy,method,args)->{
            assertThat(method.isDefault()).isTrue();return InvocationHandler.invokeDefault(proxy,method,args);
        });
        for(var call:entries(store,null))assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class);
    }
    @Test void everyEntryRejectsMissingCallerBeforeAnyDescriptorOrSql(){
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");var store=new JdbcOwnedFailedGraceAdvanceStore(h.jdbc,h.factory);
        for(var call:entries(store,null))assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class);
        assertThat(h.queries).hasValue(0);
    }
    @Test void knownActiveSamePhysicalCallerReachesFirstLimiterSqlWithoutOpeningAnotherConnection(){
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();
        try{
            var store=new JdbcOwnedFailedGraceAdvanceStore(h.jdbc,h.factory);var budget=new InvocationBudget(Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"),ZoneOffset.UTC),()->0);
            assertThatThrownBy(()->store.beforeAuthorityInTransaction(budget)).isInstanceOf(AssertionError.class).hasMessage("INERT_PROOF_BOUNDARY");assertThat(h.queries).hasValue(1);
        }finally{h.unbind();}
    }
    @Test void synchronizedFlagAndUnrelatedResourceDoNotAuthenticateCallerConnection(){
        var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");var store=new JdbcOwnedFailedGraceAdvanceStore(h.jdbc,h.factory);var unrelated=new Object();
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.bindResource(unrelated,new Object());
        try{for(var call:entries(store,null))assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class);assertThat(h.queries).hasValue(0);}
        finally{TransactionSynchronizationManager.unbindResource(unrelated);TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void everyTransitionRequiresKnownOpenActiveJpaAndExactNonAutocommitPhysicalConnection(){
        var context=new OwnedFailedGraceAdvanceInertFixture().context;
        for(var mode:List.of("missingEm","inactive","closedEm","foreignConnection","autoCommit","brokenConnection")){
            var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary(mode);h.bind();
            try{var store=new JdbcOwnedFailedGraceAdvanceStore(h.jdbc,h.factory);for(var call:entries(store,context))assertThatThrownBy(call::run).as(mode).isInstanceOf(IllegalStateException.class);assertThat(h.queries).hasValue(0);}
            finally{h.unbind();}
        }
    }
    @Test void wrongHolderTypesAndUnknownFactoryAreDeniedBeforeProofSql(){
        for(var wrong:List.of("dataSource","entityManager","factory")){
            var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();
            try{
                Object key=wrong.equals("dataSource")?h.dataSource:h.factory;
                if(!wrong.equals("factory")){TransactionSynchronizationManager.unbindResource(key);TransactionSynchronizationManager.bindResource(key,new Object());}
                var factory=wrong.equals("factory")?(jakarta.persistence.EntityManagerFactory)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{jakarta.persistence.EntityManagerFactory.class},(p,m,a)->switch(m.getName()){case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->throw new AssertionError("unknown factory touched");}):h.factory;
                var store=new JdbcOwnedFailedGraceAdvanceStore(h.jdbc,factory);for(var call:entries(store,null))assertThatThrownBy(call::run).as(wrong).isInstanceOf(IllegalStateException.class);assertThat(h.queries).hasValue(0);
            }finally{h.unbind();}
        }
    }
    @Test void completionRevalidatesCapturedCallerAndRejectsReboundHoldersOrClosedManagerBeforeFlushOrSql(){
        for(var change:List.of("connection-holder","entity-manager-holder","foreign-pair","closed")){
            var h=new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid");h.bind();
            try{
                var caller=OwnedGraceCallerConnection.capture(h.jdbc,h.factory,"lost original caller");
                assertThat(caller.requireSame()).isSameAs(h.em);
                if(change.equals("closed"))h.closedAfterCapture=true;
                else{
                    var replacement=change.equals("foreign-pair")?new OwnedFailedRenewalStoreTransactionTest.JpaBoundary("valid"):h;
                    if(!change.equals("entity-manager-holder")){
                        TransactionSynchronizationManager.unbindResource(h.dataSource);
                        TransactionSynchronizationManager.bindResource(h.dataSource,new org.springframework.jdbc.datasource.ConnectionHolder(replacement.physical));
                    }
                    if(!change.equals("connection-holder")){
                        TransactionSynchronizationManager.unbindResource(h.factory);
                        TransactionSynchronizationManager.bindResource(h.factory,new org.springframework.orm.jpa.EntityManagerHolder(replacement.em));
                    }
                }
                assertThatThrownBy(()->caller.requireSame().flush()).as(change).isInstanceOf(IllegalStateException.class).hasMessage("lost original caller");
                assertThat(h.queries).hasValue(0);
            }finally{h.unbind();}
        }
    }
    private List<Runnable> entries(OwnedFailedGraceAdvanceStore s,dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.PreparationContext c){
        var at=Instant.parse("2026-11-08T12:00:00Z");var budget=new InvocationBudget(Clock.fixed(at,ZoneOffset.UTC),()->0);
        return List.of(()->s.beforeAuthorityInTransaction(budget),()->s.beforeFreshEntryInTransaction(c==null?null:c.admission(),c==null?null:c.preparation(),budget),()->s.firstClaimInTransaction(c,"original",at,budget),()->s.requireClaimInTransaction(c,null,at,budget),()->s.acknowledgeInTransaction(c,null,null,at,at,budget),()->s.requireAcknowledgedInTransaction(c,null,at,budget),()->s.recordReadyInTransaction(c,null,null,at,at,budget),()->s.uncertainInTransaction(c,null,null,budget,at));
    }
}
