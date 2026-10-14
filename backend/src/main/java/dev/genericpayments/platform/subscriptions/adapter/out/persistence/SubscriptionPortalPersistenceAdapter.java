package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPortalStore;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository @Transactional(propagation=Propagation.MANDATORY)
public class SubscriptionPortalPersistenceAdapter implements SubscriptionPortalStore {
    private final JdbcTemplate jdbc;
    public SubscriptionPortalPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void save(SiteId site,CustomerId customer,UUID subscription,UUID operation,Instant expiresAt){
        jdbc.update("insert into subscription_portal_sessions values (?,?,?,?,?) on conflict(public_operation_id) do nothing",operation,site.value(),customer.value(),subscription,Timestamp.from(expiresAt));
    }
    public boolean valid(SiteId site,CustomerId customer,UUID subscription,UUID operation,Instant now){
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscription_portal_sessions where site_id=? and customer_id=? and subscription_id=? and public_operation_id=? and expires_at>?)",Boolean.class,site.value(),customer.value(),subscription,operation,Timestamp.from(now)));
    }
}
