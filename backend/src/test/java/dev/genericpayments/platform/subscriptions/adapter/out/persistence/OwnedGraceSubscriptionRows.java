package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import dev.genericpayments.platform.subscriptions.domain.Subscription;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only package bridge; the existing production mapper remains the sole mapping authority. */
public final class OwnedGraceSubscriptionRows {
    private final SubscriptionPersistenceAdapter subscriptions;
    public OwnedGraceSubscriptionRows(JdbcTemplate jdbc){subscriptions=new SubscriptionPersistenceAdapter(jdbc);}
    public Subscription map(ResultSet row,int index)throws SQLException{return subscriptions.map(row,index);}
}
