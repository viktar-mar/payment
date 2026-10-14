package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import static dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionPersistenceValues.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionEventStore;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository @Transactional
public class SubscriptionEventPersistenceAdapter implements SubscriptionEventStore {
    private final JdbcTemplate jdbc;
    public SubscriptionEventPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(readOnly=true)
    public boolean hasBillingFact(SiteId site,UUID subscription,UUID bill,SubscriptionEvent.Type type){
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscription_events where site_id=? and subscription_id=? and billing_record_id=? and event_type=?)",Boolean.class,site.value(),subscription,bill,type.name()));
    }
    public boolean append(SubscriptionEvent e){
        jdbc.update("insert into subscription_event_sequences(subscription_id,next_sequence) values (?,0) on conflict do nothing",e.subscriptionId());
        long previous=jdbc.queryForObject("select next_sequence from subscription_event_sequences where subscription_id=? for update",Long.class,e.subscriptionId());
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscription_events where subscription_id=? and fact_key=?)",Boolean.class,e.subscriptionId(),e.factKey())))return false;
        long sequence=Math.incrementExact(previous);
        jdbc.update("""
            insert into subscription_events(id,site_id,subscription_id,payment_account_id,sequence_number,revision,event_type,source,occurred_at,received_at,operation_id,billing_record_id,payment_id,provider_reference,fact_key,request_id)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,e.id(),e.siteId().value(),e.subscriptionId(),e.accountId().value(),sequence,e.revision(),e.type().name(),e.source().name(),time(e.occurredAt()),time(e.receivedAt()),e.operationId(),e.billingRecordId(),e.paymentId()==null?null:e.paymentId().value(),e.providerReference(),e.factKey(),e.requestId());
        jdbc.update("update subscription_event_sequences set next_sequence=? where subscription_id=?",sequence,e.subscriptionId());
        return true;
    }
    @Transactional(readOnly=true)
    public List<SubscriptionEvent> list(SiteId site,UUID subscription,int limit,long afterSequence){
        if(limit<1||limit>1000||afterSequence<0)throw new IllegalArgumentException("Invalid journal page");
        return jdbc.query("select * from subscription_events where site_id=? and subscription_id=? and sequence_number>? order by sequence_number limit ?",this::map,site.value(),subscription,afterSequence,limit);
    }
    private SubscriptionEvent map(ResultSet r,int row)throws SQLException{
        var payment=uuid(r,"payment_id");
        return new SubscriptionEvent(uuid(r,"id"),new SiteId(uuid(r,"site_id")),uuid(r,"subscription_id"),new PaymentAccountId(uuid(r,"payment_account_id")),r.getLong("sequence_number"),r.getLong("revision"),SubscriptionEvent.Type.valueOf(r.getString("event_type")),SubscriptionEvent.Source.valueOf(r.getString("source")),instant(r,"occurred_at"),instant(r,"received_at"),uuid(r,"operation_id"),uuid(r,"billing_record_id"),payment==null?null:new PaymentId(payment),r.getString("provider_reference"),r.getString("fact_key"),r.getString("request_id"));
    }
}
