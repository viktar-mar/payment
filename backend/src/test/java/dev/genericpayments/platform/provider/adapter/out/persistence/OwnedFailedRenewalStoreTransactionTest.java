package dev.genericpayments.platform.provider.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalStore;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.core.RowMapper;
import javax.sql.DataSource;
import java.sql.Connection;
import static org.assertj.core.api.Assertions.assertThat;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalServiceTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.springframework.orm.jpa.EntityManagerHolder;

/** No DataSource, Spring context, SQL, mock engine, provider or runtime bootstrap. */
class OwnedFailedRenewalStoreTransactionTest {
    @Test void graceAcceptsOnlyTheKnownActiveEntityManagerPhysicalConnectionBeforeProofSql() {
        var admission=OwnedFailedRenewalServiceTest.admissionForStoreBoundaryTest();
        for(var bad:List.of("valid","missingEm","inactive","closedEm","foreignConnection","autoCommit","brokenConnection","oneArg")) {
            var h=new JpaBoundary(bad);h.bind();
            try {
                var store=bad.equals("oneArg")?new JdbcOwnedFailedRenewalStore(h.jdbc):new JdbcOwnedFailedRenewalStore(h.jdbc,h.factory);
                for(Runnable entry:List.<Runnable>of(()->store.lockGraceLastSecondInTransaction(admission),()->store.prepareGraceLastSecondInTransaction(admission,null,null),()->store.requireGraceLastSecondInTransaction(admission,null))) {
                    if(bad.equals("valid"))assertThatThrownBy(entry::run).isInstanceOf(AssertionError.class).hasMessage("INERT_PROOF_BOUNDARY");
                    else assertThatThrownBy(entry::run).isInstanceOf(IllegalStateException.class).hasMessage("Owned failed renewal requires caller DataSource transaction");
                }
                assertThat(h.queries).hasValue(bad.equals("valid")?3:0);
            } finally {h.unbind();}
        }
    }
    private static final class JpaBoundary {
        final java.util.concurrent.atomic.AtomicInteger queries=new java.util.concurrent.atomic.AtomicInteger();
        final String mode;final DataSource dataSource;final Connection physical,sessionConnection;final EntityManagerFactory factory;final EntityManager em;final JdbcTemplate jdbc;
        JpaBoundary(String mode) {
            this.mode=mode;
            dataSource=proxy(DataSource.class,(method,args)->identity(method,args,"inert-known-source"));
            physical=proxy(Connection.class,(method,args)->switch(method){case "getAutoCommit"->{if(mode.equals("brokenConnection"))throw new java.sql.SQLException("inert-closed");yield mode.equals("autoCommit");}default->identity(method,args,"inert-physical");});
            sessionConnection=mode.equals("foreignConnection")?proxy(Connection.class,(method,args)->method.equals("getAutoCommit")?false:identity(method,args,"inert-other-physical")):physical;
            factory=proxy(EntityManagerFactory.class,(method,args)->identity(method,args,"inert-known-factory"));
            var tx=proxy(EntityTransaction.class,(method,args)->method.equals("isActive")?!mode.equals("inactive"):identity(method,args,"inert-entity-transaction"));
            var session=proxy(Session.class,(method,args)->{if(method.equals("doReturningWork"))return ((ReturningWork<?>)args[0]).execute(sessionConnection);return identity(method,args,"inert-session");});
            em=proxy(EntityManager.class,(method,args)->switch(method){case "isOpen"->!mode.equals("closedEm");case "getTransaction"->tx;case "unwrap"->{assertThat(args[0]).isEqualTo(Session.class);yield session;}default->identity(method,args,"inert-entity-manager");});
            jdbc=new JdbcTemplate(dataSource){@Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){queries.incrementAndGet();throw new AssertionError("INERT_PROOF_BOUNDARY");}};
        }
        void bind(){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.bindResource(dataSource,new ConnectionHolder(physical));if(!mode.equals("missingEm"))TransactionSynchronizationManager.bindResource(factory,new EntityManagerHolder(em));}
        void unbind(){TransactionSynchronizationManager.unbindResource(dataSource);if(!mode.equals("missingEm"))TransactionSynchronizationManager.unbindResource(factory);TransactionSynchronizationManager.setActualTransactionActive(false);}
        private interface Call {Object call(String name,Object[] args)throws Throwable;}
        private static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->switch(m.getName()){case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->call.call(m.getName(),a);}));}
        private static Object identity(String method,Object[] args,String name){if(method.equals("toString"))return name;throw new AssertionError("Unexpected inert call "+name+"."+method);}
    }
    @Test void synchronizedForeignHolderMustRefuseBeforeAnyProofQuery() {
        var dataSource=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},
            (proxy,method,args)->switch(method.getName()){case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];case "toString"->"inert-secondary-data-source";default->throw new AssertionError("No DataSource method may run: "+method.getName());});
        var connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},
            (proxy,method,args)->switch(method.getName()){case "getAutoCommit"->true;case "toString"->"inert-foreign-connection";default->throw new AssertionError("No connection work: "+method.getName());});
        var queryCount=new java.util.concurrent.atomic.AtomicInteger();
        var jdbc=new JdbcTemplate(dataSource) {
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                queryCount.incrementAndGet();throw new AssertionError("FOREIGN_HOLDER_REACHED_PROOF_SQL");
            }
        };
        var holder=new ConnectionHolder(connection);holder.setSynchronizedWithTransaction(true);
        var admission=OwnedFailedRenewalServiceTest.admissionForStoreBoundaryTest();var store=new JdbcOwnedFailedRenewalStore(jdbc);
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.bindResource(dataSource,holder);
        try {
            for(Runnable entry:List.<Runnable>of(()->store.lockGraceLastSecondInTransaction(admission),()->store.prepareGraceLastSecondInTransaction(admission,null,null),()->store.requireGraceLastSecondInTransaction(admission,null)))
                assertThatThrownBy(entry::run).isInstanceOf(IllegalStateException.class).hasMessage("Owned failed renewal requires caller DataSource transaction");
            assertThat(queryCount).hasValue(0);
        } finally {TransactionSynchronizationManager.unbindResource(dataSource);TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
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
