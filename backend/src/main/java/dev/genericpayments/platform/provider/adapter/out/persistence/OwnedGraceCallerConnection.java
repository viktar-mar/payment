package dev.genericpayments.platform.provider.adapter.out.persistence;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No connection/manager acquisition or fallback: only the already-bound known caller is valid. */
final class OwnedGraceCallerConnection {
    private OwnedGraceCallerConnection(){}
    static void require(JdbcTemplate jdbc,EntityManagerFactory factory,String message){
        var source=jdbc.getDataSource();
        if(!TransactionSynchronizationManager.isActualTransactionActive()||factory==null||source==null
                ||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                ||!(TransactionSynchronizationManager.getResource(factory) instanceof EntityManagerHolder owner))throw new IllegalStateException(message);
        try{
            var em=owner.getEntityManager();if(!em.isOpen()||!em.getTransaction().isActive())throw new IllegalStateException(message);
            var bound=DataSourceUtils.getTargetConnection(holder.getConnection());
            if(bound.getAutoCommit()||!Boolean.TRUE.equals(em.unwrap(Session.class).doReturningWork(connection->DataSourceUtils.getTargetConnection(connection)==bound&&!connection.getAutoCommit())))throw new IllegalStateException(message);
        }catch(java.sql.SQLException|RuntimeException failure){throw new IllegalStateException(message);}
    }
}
