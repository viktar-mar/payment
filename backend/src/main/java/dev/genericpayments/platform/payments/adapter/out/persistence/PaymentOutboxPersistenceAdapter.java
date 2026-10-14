package dev.genericpayments.platform.payments.adapter.out.persistence;
import dev.genericpayments.platform.payments.application.port.out.PaymentOutboxPort;
import dev.genericpayments.platform.shared.application.port.out.PendingOutboxTelemetryPort;
import dev.genericpayments.platform.payments.application.model.SafeFactJson;
import dev.genericpayments.platform.payments.domain.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
public class PaymentOutboxPersistenceAdapter implements PaymentOutboxPort {
    private final JdbcTemplate jdbc;private final PendingOutboxTelemetryPort pending;
    public PaymentOutboxPersistenceAdapter(JdbcTemplate jdbc,PendingOutboxTelemetryPort pending){this.jdbc=jdbc;this.pending=pending;}
    @Override @Transactional(propagation=Propagation.MANDATORY) public void snapshot(Payment p,PaymentEvent e,Instant now) {
        String type=switch(e.type()){case PAYMENT_METHOD_REQUIRED -> "payment.requires_payment_method";case CUSTOMER_ACTION_REQUIRED -> "payment.requires_action";case PAYMENT_PROCESSING -> "payment.processing";case PAYMENT_SUCCEEDED -> "payment.succeeded";case PAYMENT_FAILED -> "payment.failed";case PAYMENT_CANCELLED -> "payment.cancelled";case REFUND_PROCESSING -> "refund.processing";case REFUND_SUCCEEDED -> "refund.succeeded";case REFUND_FAILED -> "refund.failed";default -> null;};
        if(type==null)return;
        var configs=jdbc.queryForList("select c.endpoint_url,c.active_secret_version_id from site_callback_configs c join site_callback_secret_versions s on s.id=c.active_secret_version_id where c.site_id=? and c.enabled and s.destroyed_at is null and s.ciphertext is not null for share of c,s",p.siteId().value());
        if(configs.isEmpty())return;var config=configs.getFirst();
        var payload=new TreeMap<String,Object>();payload.put("schema_version",1);payload.put("event_id",e.id().toString());payload.put("event_type",type);payload.put("site_id",p.siteId().value().toString());payload.put("payment_id",p.id().value().toString());payload.put("external_order_id",p.externalOrderId());payload.put("payment_status",p.status().name());payload.put("amount_minor",e.money()==null?null:e.money().minorUnits());payload.put("currency",e.money()==null?null:e.money().currency());payload.put("occurred_at",e.occurredAt().toString());
        // The transition already loaded and locked this Payment in the owning transaction.
        // Its immutable snapshot, never mutable Campaign/message storage, defines callback context.
        switch(p.source()) {
        case CATALOG, DYNAMIC -> { }
        case DONATION -> {
            payload.put("schema_version",2);
            payload.put("donation_id",p.donationId().value().toString());
            payload.put("campaign_key",p.donationSnapshot().campaignKey().value());
            payload.put("customer_id",p.customerId()==null?null:p.customerId().toString());
        }
        case SUBSCRIPTION -> {
            var c=p.subscriptionContext();
            payload.put("schema_version",3);payload.put("external_order_id",null);
            payload.put("subscription_id",c.subscriptionId().toString());payload.put("billing_record_id",c.billingRecordId().toString());
            payload.put("customer_id",p.customerId().toString());payload.put("product_id",c.productId().value().toString());
            payload.put("billing_kind",c.kind().name());payload.put("price_id",c.priceId().value().toString());payload.put("price_version",c.priceVersion());
            payload.put("period_start",c.periodStart().toString());payload.put("period_end",c.periodEnd().toString());
        }
        }
        jdbc.update("insert into outbox_events(id,site_id,payment_id,payment_account_id,callback_type,payload,endpoint_url,secret_version_id,status,next_attempt_at,attempt_count,created_at,updated_at) values (?,?,?,?,?,?::jsonb,?,?,'PENDING',?,0,?,?)",e.id(),p.siteId().value(),p.id().value(),p.paymentAccountId().value(),type,SafeFactJson.json(payload),config.get("endpoint_url"),config.get("active_secret_version_id"),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){pending.requestRefresh();}
            });
    }
}
