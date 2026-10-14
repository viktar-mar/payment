package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.util.*;
import java.util.function.LongFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PaymentEventPersistenceAdapter implements AppendPaymentEventPort,LoadPaymentEventPort {
    private final JdbcTemplate jdbc; private final PaymentPersistenceMapper mapper;
    PaymentEventPersistenceAdapter(JdbcTemplate jdbc,PaymentPersistenceMapper mapper) {this.jdbc=jdbc; this.mapper=mapper;}
    @Override @Transactional public PaymentEvent append(PaymentId paymentId,LongFunction<PaymentEvent> factory) {
        Long sequence=jdbc.queryForObject("""
            insert into payment_event_sequences(payment_id,next_sequence) values (?,1)
            on conflict(payment_id) do update set next_sequence=payment_event_sequences.next_sequence+1
            returning next_sequence
            """,Long.class,paymentId.value());
        if(sequence==null) throw new IllegalStateException("sequence allocation failed");
        PaymentEvent e=Objects.requireNonNull(factory.apply(sequence));
        if(!e.paymentId().equals(paymentId) || e.sequence()!=sequence)
            throw new IllegalArgumentException("event must use the allocated Payment and sequence");
        jdbc.update("""
            insert into payment_events(id,payment_id,site_id,payment_account_id,sequence_number,event_type,source,
              amount_minor,currency,provider_event_id,provider_object_id,occurred_at,received_at,request_id,
              idempotency_key,payload_hash,technical_metadata)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
            """,e.id(),e.paymentId().value(),e.siteId().value(),e.paymentAccountId().value(),e.sequence(),e.type().name(),
            e.source().name(),e.money()==null?null:e.money().minorUnits(),e.money()==null?null:e.money().currency(),
            e.providerEventId(),e.providerObjectId(),Timestamp.from(e.occurredAt()),Timestamp.from(e.receivedAt()),
            e.requestId(),e.idempotencyKey(),e.payloadHash(),mapper.metadata(e.technicalMetadata()));
        return e;
    }
    @Override @Transactional(readOnly=true) public List<PaymentEvent> findByPayment(SiteId siteId,PaymentId id) {
        return jdbc.query("""
            select id,payment_id,site_id,payment_account_id,sequence_number,event_type,source,amount_minor,currency,
              provider_event_id,provider_object_id,occurred_at,received_at,request_id,idempotency_key,payload_hash,technical_metadata
            from payment_events where site_id=? and payment_id=? order by sequence_number
            """,this::event,siteId.value(),id.value());
    }
    private PaymentEvent event(ResultSet r,int row) throws SQLException {
        Long amount=r.getObject("amount_minor",Long.class);
        return new PaymentEvent(r.getObject("id",UUID.class),new PaymentId(r.getObject("payment_id",UUID.class)),
            r.getLong("sequence_number"),new SiteId(r.getObject("site_id",UUID.class)),
            new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),PaymentEventType.valueOf(r.getString("event_type")),
            PaymentEventSource.valueOf(r.getString("source")),amount==null?null:Money.of(amount,r.getString("currency")),
            r.getString("provider_event_id"),r.getString("provider_object_id"),r.getTimestamp("occurred_at").toInstant(),
            r.getTimestamp("received_at").toInstant(),r.getString("request_id"),r.getString("idempotency_key"),
            r.getString("payload_hash"),mapper.metadata(r.getString("technical_metadata")));
    }
}
