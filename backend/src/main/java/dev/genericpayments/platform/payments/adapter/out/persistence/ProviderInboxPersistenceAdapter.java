package dev.genericpayments.platform.payments.adapter.out.persistence;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.ProviderInboxPort;
import dev.genericpayments.platform.payments.domain.PaymentEventType;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@Repository
public class ProviderInboxPersistenceAdapter implements ProviderInboxPort {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ProviderInboxPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;this.json=new ObjectMapper();}
    @Override @Transactional public boolean insert(ProviderEventEnvelope e,String payload,String hash,Consumer<Boolean> afterCommit) {
        UUID id=UUID.nameUUIDFromBytes((e.provider()+":"+e.accountId().value()+":"+e.eventId()).getBytes(StandardCharsets.UTF_8));
        boolean inserted=jdbc.update("""
            insert into provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_type,occurred_at,received_at,payload_hash,safe_payload,next_attempt_at)
            values (?,?,?,?,?,?,?,?,?,?::jsonb,?) on conflict(provider_type,payment_account_id,provider_event_id) do nothing
            """,id,e.provider().name(),e.accountId().value(),e.eventId(),e.objectId(),e.type().name(),Timestamp.from(e.occurredAt()),Timestamp.from(e.receivedAt()),hash,payload,Timestamp.from(e.receivedAt()))==1;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){afterCommit.accept(inserted);}});
        return inserted;
    }
    @Override @Transactional(readOnly=true) public Optional<Fact> find(UUID id){return jdbc.query("select * from provider_inbox where event_family='PAYMENT' and id=?",this::fact,id).stream().findFirst();}
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW) public List<Claim> claim(String owner,int batch,Instant now,Duration lease) {
        SafeFactJson.text(owner,128);if(batch<1||batch>100||now==null||lease==null||lease.compareTo(Duration.ofSeconds(1))<0||lease.compareTo(Duration.ofMinutes(10))>0)throw SafeFactJson.invalid();
        return jdbc.query("""
            with eligible as (select id from provider_inbox
              where event_family='PAYMENT' and next_attempt_at<=? and (status in ('PENDING','FAILED') or (status='PROCESSING' and lease_expires_at<=?))
              order by next_attempt_at,received_at,id limit ? for update skip locked)
            update provider_inbox i set status='PROCESSING',lease_owner=?,lease_expires_at=?,attempt_count=attempt_count+1,error_category=null
            from eligible where i.id=eligible.id returning i.id
            """,(r,n)->new Claim(r.getObject(1,UUID.class),owner),Timestamp.from(now),Timestamp.from(now),batch,owner,Timestamp.from(now.plus(lease)));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY) public Fact lock(Claim claim,Instant now) {
        var rows=jdbc.query("select * from provider_inbox where event_family='PAYMENT' and id=? and status='PROCESSING' and lease_owner=? and lease_expires_at>? for update",this::fact,claim.id(),claim.owner(),Timestamp.from(now));
        if(rows.size()!=1)throw new IllegalStateException("CLAIM_LOST");return rows.getFirst();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY) public void complete(Claim claim) {
        if(jdbc.update("update provider_inbox set status='PROCESSED',lease_owner=null,lease_expires_at=null,error_category=null where id=? and status='PROCESSING' and lease_owner=?",claim.id(),claim.owner())!=1)throw new IllegalStateException("CLAIM_LOST");
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW) public boolean fail(Claim claim,String category,Instant now) {
        if(now==null||!Set.of("UNSUPPORTED_EVENT","UNRESOLVED_OBJECT","IDENTITY_MISMATCH","PROCESSING_FAILED").contains(category))throw SafeFactJson.invalid();
        // Claim increments once; derive the bounded 5,10,20,40,80,160,300-second delay atomically.
        return jdbc.update("""
            update provider_inbox set status='FAILED',lease_owner=null,lease_expires_at=null,error_category=?,
              next_attempt_at=?::timestamptz + least(300,5*(1 << least(greatest(attempt_count-1,0),6))) * interval '1 second'
            where id=? and status='PROCESSING' and lease_owner=?
            """,category,Timestamp.from(now),claim.id(),claim.owner())==1;
    }
    private Fact fact(ResultSet r,int n)throws SQLException {
        Map<String,Object> payload;try {payload=json.readValue(r.getString("safe_payload"),new TypeReference<Map<String,Object>>(){});}catch(Exception invalid){throw SafeFactJson.invalid();}
        return new Fact(r.getObject("id",UUID.class),new ProviderEventEnvelope(PaymentProviderType.valueOf(r.getString("provider_type")),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getString("provider_event_id"),r.getString("provider_object_id"),PaymentEventType.valueOf(r.getString("event_type")),r.getTimestamp("occurred_at").toInstant(),r.getTimestamp("received_at").toInstant(),payload),r.getString("payload_hash"));
    }
}
