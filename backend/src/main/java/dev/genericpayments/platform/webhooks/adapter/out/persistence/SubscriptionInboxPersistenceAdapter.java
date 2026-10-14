package dev.genericpayments.platform.webhooks.adapter.out.persistence;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
public class SubscriptionInboxPersistenceAdapter implements SubscriptionInboxPort {
    private final JdbcTemplate jdbc;
    public SubscriptionInboxPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Timestamp timestamp(Instant value){return Timestamp.from(value);}
    @Transactional public boolean insert(SubscriptionProviderEnvelope e) {
        return jdbc.update("""
            insert into provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_type,occurred_at,received_at,payload_hash,safe_payload,next_attempt_at,event_family)
            values (?,?,?,?,?,?,?,?,?,'{}'::jsonb,?,'SUBSCRIPTION') on conflict(provider_type,payment_account_id,provider_event_id) do nothing
            """,UUID.randomUUID(),e.provider().name(),e.accountId().value(),e.eventId(),e.objectReference(),e.eventType(),timestamp(e.occurredAt()),timestamp(e.receivedAt()),hash(e),timestamp(e.receivedAt()))==1;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW) public List<Claim> claim(String owner,int batch,Instant now,Duration lease) {
        if(owner==null||owner.isBlank()||owner.length()>128||owner.codePoints().anyMatch(Character::isISOControl)||now==null||batch<1||batch>100||lease==null||lease.compareTo(Duration.ofSeconds(1))<0||lease.compareTo(Duration.ofMinutes(10))>0)throw new IllegalArgumentException("Invalid inbox claim");
        return jdbc.query("""
            with ready as (select id from provider_inbox where event_family='SUBSCRIPTION' and next_attempt_at<=?
              and (status in ('PENDING','FAILED') or (status='PROCESSING' and lease_expires_at<=?))
              order by next_attempt_at,received_at,id limit ? for update skip locked)
            update provider_inbox p set status='PROCESSING',attempt_count=attempt_count+1,lease_owner=?,lease_expires_at=?,error_category=null
            from ready where p.id=ready.id returning p.id
            """,(r,n)->new Claim(r.getObject(1,UUID.class),owner),timestamp(now),timestamp(now),batch,owner,timestamp(now.plus(lease)));
    }
    @Transactional(propagation=Propagation.MANDATORY) public SubscriptionProviderEnvelope lock(Claim claim,Instant now) {
        var results=jdbc.query("select * from provider_inbox where id=? and event_family='SUBSCRIPTION' and status='PROCESSING' and lease_owner=? and lease_expires_at>? for update",(r,n)->new SubscriptionProviderEnvelope(PaymentProviderType.valueOf(r.getString("provider_type")),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getString("provider_event_id"),r.getString("provider_object_id"),r.getString("event_type"),r.getTimestamp("occurred_at").toInstant(),r.getTimestamp("received_at").toInstant()),claim.id(),claim.owner(),timestamp(now));
        if(results.size()!=1)throw new IllegalStateException("CLAIM_LOST");return results.getFirst();
    }
    @Transactional(propagation=Propagation.MANDATORY) public void complete(Claim claim) {
        if(jdbc.update("update provider_inbox set status='PROCESSED',lease_owner=null,lease_expires_at=null,error_category=null where id=? and event_family='SUBSCRIPTION' and status='PROCESSING' and lease_owner=?",claim.id(),claim.owner())!=1)throw new IllegalStateException("CLAIM_LOST");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW) public void fail(Claim claim,String category,Instant now) {
        if(!Set.of("UNRESOLVED_OBJECT","IDENTITY_MISMATCH","PROCESSING_FAILED").contains(category))throw new IllegalArgumentException("Invalid inbox failure");
        jdbc.update("update provider_inbox set status='FAILED',lease_owner=null,lease_expires_at=null,error_category=?,next_attempt_at=?::timestamptz + least(300,5*(1 << least(greatest(attempt_count-1,0),6))) * interval '1 second' where id=? and event_family='SUBSCRIPTION' and status='PROCESSING' and lease_owner=?",category,timestamp(now),claim.id(),claim.owner());
    }
    private static String hash(SubscriptionProviderEnvelope e) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((e.eventType()+"\n"+e.objectReference()+"\n"+e.occurredAt()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
