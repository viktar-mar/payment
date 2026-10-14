package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.TrialEligibilityStore;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository @Transactional
public class TrialEligibilityPersistenceAdapter implements TrialEligibilityStore {
    private final JdbcTemplate jdbc;
    public TrialEligibilityPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<dev.genericpayments.platform.subscriptions.domain.TrialEligibility> find(SiteId site,CustomerId customer,ProductId product){
        return jdbc.query("select * from subscription_trial_eligibility where site_id=? and customer_id=? and product_id=?",(r,n)->new dev.genericpayments.platform.subscriptions.domain.TrialEligibility(site,customer,product,r.getObject("reserved_by_operation",UUID.class),r.getTimestamp("consumed_at")==null?null:r.getTimestamp("consumed_at").toInstant(),r.getLong("version")),site.value(),customer.value(),product.value()).stream().findFirst();
    }
    @Transactional(readOnly=true)
    public boolean isEligible(SiteId site,CustomerId customer,ProductId product){return !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscription_trial_eligibility where site_id=? and customer_id=? and product_id=? and (reserved_by_operation is not null or consumed_at is not null))",Boolean.class,site.value(),customer.value(),product.value()));}
    public boolean reserve(SiteId site,CustomerId customer,ProductId product,UUID operation){
        Objects.requireNonNull(operation);
        return jdbc.update("""
            insert into subscription_trial_eligibility(site_id,customer_id,product_id,reserved_by_operation) values (?,?,?,?)
            on conflict(site_id,customer_id,product_id) do update set reserved_by_operation=excluded.reserved_by_operation,version=subscription_trial_eligibility.version+1
            where subscription_trial_eligibility.consumed_at is null and (subscription_trial_eligibility.reserved_by_operation is null or subscription_trial_eligibility.reserved_by_operation=excluded.reserved_by_operation)
            """,site.value(),customer.value(),product.value(),operation)==1;
    }
    public void consume(UUID operation,Instant at){Objects.requireNonNull(operation);Objects.requireNonNull(at);jdbc.update("update subscription_trial_eligibility set consumed_at=?,version=version+1 where reserved_by_operation=? and consumed_at is null",SubscriptionPersistenceValues.time(at),operation);}
    public void releaseConfirmedFailure(UUID operation){Objects.requireNonNull(operation);jdbc.update("update subscription_trial_eligibility set reserved_by_operation=null,version=version+1 where reserved_by_operation=? and consumed_at is null",operation);}
}
