package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.BillingMetadataPort;
import dev.genericpayments.platform.subscriptions.domain.BillingRecord;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
public class BillingMetadataPersistenceAdapter implements BillingMetadataPort {
    private final JdbcTemplate jdbc;
    public BillingMetadataPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public Captured captureOnce(BillingRecord bill,long version,Instant deadline){
        if(version<0)throw new IllegalArgumentException("Invalid policy version");
        jdbc.update("update subscription_billing_records set captured_policy_version=?,grace_deadline=? where id=? and site_id=? and subscription_id=? and payment_account_id=? and captured_policy_version is null",version,deadline==null?null:Timestamp.from(deadline),bill.id(),bill.siteId().value(),bill.subscriptionId(),bill.accountId().value());
        return jdbc.query("select captured_policy_version,grace_deadline from subscription_billing_records where id=? and site_id=? and subscription_id=? and payment_account_id=? and captured_policy_version is not null for update",(r,n)->new Captured(r.getLong(1),r.getTimestamp(2)==null?null:r.getTimestamp(2).toInstant()),bill.id(),bill.siteId().value(),bill.subscriptionId(),bill.accountId().value()).stream().findFirst().orElseThrow();
    }
}
