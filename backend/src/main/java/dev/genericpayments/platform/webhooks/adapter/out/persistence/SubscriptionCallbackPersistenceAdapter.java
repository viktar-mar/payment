package dev.genericpayments.platform.webhooks.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCallbackPort;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.payments.application.model.SafeFactJson;
import dev.genericpayments.platform.shared.application.port.out.PendingOutboxTelemetryPort;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
@Repository
public class SubscriptionCallbackPersistenceAdapter implements SubscriptionCallbackPort {
    private final JdbcTemplate jdbc;private final PendingOutboxTelemetryPort pending;
    public SubscriptionCallbackPersistenceAdapter(JdbcTemplate jdbc,PendingOutboxTelemetryPort pending){this.jdbc=jdbc;this.pending=pending;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void snapshot(Subscription s,SubscriptionEvent e,AccessDecision access,Instant now){
        if(!s.id().equals(e.subscriptionId())||!s.siteId().equals(e.siteId())||!s.paymentAccountId().equals(e.accountId())||s.revision()!=e.revision()||!access.equals(new SubscriptionAccessPolicy().evaluate(s,now)))throw new IllegalArgumentException("Callback ownership mismatch");
        var configs=jdbc.queryForList("select c.endpoint_url,c.active_secret_version_id from site_callback_configs c join site_callback_secret_versions v on v.id=c.active_secret_version_id where c.site_id=? and c.enabled and v.destroyed_at is null and v.ciphertext is not null for share of c,v",s.siteId().value());
        if(configs.isEmpty())return;
        var config=configs.getFirst();String type=e.type()==SubscriptionEvent.Type.ACCESS_SUSPENDED?"subscription.access_changed":"subscription.updated";
        var payload=new TreeMap<String,Object>();payload.put("schema_version",4);payload.put("event_id",e.id().toString());payload.put("event_type",type);payload.put("site_id",s.siteId().value().toString());
        payload.put("subscription_id",s.id().toString());payload.put("customer_id",s.customerId().value().toString());payload.put("product_id",s.productId().value().toString());payload.put("revision",s.revision());payload.put("state",s.displayState(now));
        payload.put("access_allowed",access.allowed());payload.put("access_valid_until",access.validUntil()==null?null:access.validUntil().toString());payload.put("occurred_at",now.toString());payload.put("billing_record_id",e.billingRecordId()==null?null:e.billingRecordId().toString());payload.put("payment_id",e.paymentId()==null?null:e.paymentId().value().toString());
        jdbc.update("insert into outbox_events(id,site_id,source_kind,subscription_id,subscription_event_id,payment_account_id,callback_type,payload,endpoint_url,secret_version_id,status,next_attempt_at,attempt_count,created_at,updated_at) values (?,?,'SUBSCRIPTION',?,?,?,?,?::jsonb,?,?,'PENDING',?,0,?,?)",e.id(),s.siteId().value(),s.id(),e.id(),s.paymentAccountId().value(),type,SafeFactJson.json(payload),config.get("endpoint_url"),config.get("active_secret_version_id"),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){pending.requestRefresh();}});
    }
}
