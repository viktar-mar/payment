package dev.genericpayments.platform.provider.adapter.out.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No connection/manager acquisition or fallback: only the already-bound known caller is valid. */
final class OwnedGraceCallerConnection {
    private final DataSource source;
    private final EntityManagerFactory factory;
    private final ConnectionHolder holder;
    private final EntityManagerHolder owner;
    private final EntityManager manager;
    private final Connection physical;
    private final String message;

    private OwnedGraceCallerConnection(DataSource source,EntityManagerFactory factory,ConnectionHolder holder,
            EntityManagerHolder owner,EntityManager manager,Connection physical,String message){
        this.source=source;this.factory=factory;this.holder=holder;this.owner=owner;this.manager=manager;this.physical=physical;this.message=message;
    }
    static void require(JdbcTemplate jdbc,EntityManagerFactory factory,String message){
        capture(jdbc,factory,message);
    }
    static OwnedGraceCallerConnection capture(JdbcTemplate jdbc,EntityManagerFactory factory,String message){
        var source=jdbc.getDataSource();
        if(!TransactionSynchronizationManager.isActualTransactionActive()||factory==null||source==null
                ||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                ||!(TransactionSynchronizationManager.getResource(factory) instanceof EntityManagerHolder owner))throw new IllegalStateException(message);
        try{
            var manager=owner.getEntityManager();
            if(!manager.isOpen()||!manager.getTransaction().isActive())throw new IllegalStateException(message);
            var physical=DataSourceUtils.getTargetConnection(holder.getConnection());
            verifyPhysical(manager,physical,message);
            return new OwnedGraceCallerConnection(source,factory,holder,owner,manager,physical,message);
        }catch(RuntimeException failure){throw new IllegalStateException(message);}
    }
    EntityManager requireSame(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                ||TransactionSynchronizationManager.getResource(source)!=holder||TransactionSynchronizationManager.getResource(factory)!=owner)
            throw new IllegalStateException(message);
        try{
            if(owner.getEntityManager()!=manager||!manager.isOpen()||!manager.getTransaction().isActive()
                    ||DataSourceUtils.getTargetConnection(holder.getConnection())!=physical)throw new IllegalStateException(message);
            verifyPhysical(manager,physical,message);
            return manager;
        }catch(RuntimeException failure){throw new IllegalStateException(message);}
    }
    private static void verifyPhysical(EntityManager manager,Connection physical,String message){
        try{
            if(physical.getAutoCommit()||!Boolean.TRUE.equals(manager.unwrap(Session.class).doReturningWork(
                    connection->DataSourceUtils.getTargetConnection(connection)==physical&&!connection.getAutoCommit())))throw new IllegalStateException(message);
        }catch(java.sql.SQLException failure){throw new IllegalStateException(message);}
    }
}
