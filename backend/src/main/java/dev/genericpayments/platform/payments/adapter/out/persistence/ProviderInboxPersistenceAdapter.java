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
import org.springframework.beans.factory.annotation.Qualifier;

@Repository
public class ProviderInboxPersistenceAdapter implements ProviderInboxPort {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final Clock referenceClock;
    public ProviderInboxPersistenceAdapter(JdbcTemplate jdbc,@Qualifier("providerReferenceClock") Clock referenceClock){this.jdbc=jdbc;this.json=new ObjectMapper();this.referenceClock=referenceClock;}
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

    @Override @Transactional public boolean insertReference(ProviderReferenceReceipt receipt) {
        Objects.requireNonNull(receipt);
        UUID id=UUID.nameUUIDFromBytes((receipt.provider()+":"+receipt.accountId().value()+":"+receipt.eventId()).getBytes(StandardCharsets.UTF_8));
        // Hash only typed facts; neither the original snapshot nor its secret-bearing bytes are retained.
        String hash;
        try {hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
            (receipt.provider()+":"+receipt.accountId().value()+":"+receipt.eventId()+":"+receipt.objectId()+":"+
                receipt.objectType()+":"+receipt.providerEventType()+":"+receipt.apiVersion()+":"+receipt.occurredAt()).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException("Reference digest unavailable");}
        return jdbc.update("""
            insert into provider_inbox(id,provider_type,payment_account_id,provider_event_id,provider_object_id,event_type,
                occurred_at,received_at,payload_hash,safe_payload,next_attempt_at,event_family,reference_object_type,provider_event_type,provider_api_version)
            values (?,?,?,?,?,'PROVIDER_REFERENCE',?,?,?,'{}'::jsonb,?,'PROVIDER_REFERENCE',?,?,?)
            on conflict(provider_type,payment_account_id,provider_event_id) do nothing
            """,id,receipt.provider().name(),receipt.accountId().value(),receipt.eventId(),receipt.objectId(),
            Timestamp.from(receipt.occurredAt()),Timestamp.from(receipt.receivedAt()),hash,Timestamp.from(receipt.receivedAt()),
            receipt.objectType().name(),receipt.providerEventType(),receipt.apiVersion())==1;
    }

    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<Claim> claimReference(Duration lease) {
        if(lease==null || lease.compareTo(Duration.ofSeconds(1))<0 || lease.compareTo(Duration.ofMinutes(10))>0)throw SafeFactJson.invalid();
        Instant selectedAt=referenceClock.instant();
        var rows=jdbc.query("""
            select * from provider_inbox where event_family='PROVIDER_REFERENCE' and next_attempt_at<=?
              and (status in ('PENDING','FAILED') or (status='PROCESSING' and lease_expires_at<=?))
            order by next_attempt_at,received_at,id limit 1 for update skip locked
            """,this::referenceRow,Timestamp.from(selectedAt),Timestamp.from(selectedAt));
        if(rows.isEmpty())return Optional.empty();
        var row=rows.getFirst();Instant now=referenceClock.instant();
        if(row.nextAttempt().isAfter(now) || (row.status().equals("PROCESSING") && row.expires().isAfter(now)))return Optional.empty();
        if(row.attempts()>=12) {
            jdbc.update("update provider_inbox set status='DEAD',lease_owner=null,lease_expires_at=null,error_category='PROCESSING_FAILED' where id=?",row.id());
            return Optional.empty();
        }
        String owner=UUID.randomUUID().toString();
        jdbc.update("""
            update provider_inbox set status='PROCESSING',lease_owner=?,lease_expires_at=?,attempt_count=attempt_count+1,error_category=null where id=?
            """,owner,Timestamp.from(now.plus(lease)),row.id());
        return Optional.of(new Claim(row.id(),owner));
    }

    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<ProviderReferenceReceipt> reference(Claim claim) {
        return liveReference(claim).map(ReferenceRow::receipt);
    }

    // Identity only: immutable V16/V20 context, original Account, committed root mapping.
    // No lifecycle/payment locks: facts acquire those before writing the inbox, so reversing
    // that lock order here would introduce a deadlock. A later claim may see uncommitted proof.
    private static final String BILLING_PAYMENT_CANDIDATES="""
        select s.provider_reference as reference
        from subscription_provider_objects o
        join payments p on p.id=o.payment_id and p.site_id=o.site_id
          and p.subscription_id=o.subscription_id and p.payment_account_id=o.payment_account_id
          and p.billing_record_id=o.billing_record_id and p.provider_payment_reference=o.provider_reference
          and p.source='SUBSCRIPTION'
        join subscription_billing_records b on b.id=o.billing_record_id and b.site_id=o.site_id
          and b.subscription_id=o.subscription_id and b.payment_account_id=o.payment_account_id
          and b.product_id=p.subscription_product_id and b.kind=p.subscription_billing_kind
          and b.billed_price_id=p.subscription_price_id and b.billed_price_version=p.subscription_price_version
          and b.previous_price_id is not distinct from p.subscription_previous_price_id
          and b.previous_price_version is not distinct from p.subscription_previous_price_version
          and b.period_start=p.subscription_period_start and b.period_end=p.subscription_period_end
          and b.provider_invoice_reference=p.subscription_invoice_reference
          and b.currency=p.currency and b.billed_tax_mode=p.tax_mode and b.amount_due_minor>=p.amount_minor
        join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id
          and s.payment_account_id=o.payment_account_id and s.customer_id=p.customer_id
          and s.product_id=b.product_id and s.product_id=p.subscription_product_id
        join payment_accounts a on a.id=o.payment_account_id and a.provider='STRIPE' and a.account_mode='SANDBOX'
        join subscription_provider_objects root on root.provider_type=o.provider_type
          and root.payment_account_id=o.payment_account_id and root.site_id=o.site_id
          and root.subscription_id=o.subscription_id and root.object_type='SUBSCRIPTION'
          and root.provider_reference=s.provider_reference
        where o.provider_type='STRIPE' and o.payment_account_id=? and o.object_type='PAYMENT'
          and o.provider_reference=? and o.payment_id is not null and o.billing_record_id is not null
          and s.provider_reference ~ '^sub_[A-Za-z0-9]{1,251}$'
        limit 2
        """;

    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean promoteBillingPaymentReference(Claim claim,ProviderReferenceReceipt expected) {
        Objects.requireNonNull(claim);Objects.requireNonNull(expected);
        if(expected.provider()!=PaymentProviderType.STRIPE||expected.objectType()!=ProviderReferenceReceipt.ObjectType.PAYMENT)return false;
        var row=liveReference(claim).orElseThrow(()->new IllegalStateException("CLAIM_LOST"));
        if(!row.receipt().equals(expected)||row.attempts()<1||row.attempts()>12)throw new IllegalStateException("CLAIM_LOST");
        var candidates=jdbc.query(BILLING_PAYMENT_CANDIDATES,(r,n)->r.getString("reference"),expected.accountId().value(),expected.objectId());
        Instant now=referenceClock.instant();
        if(!row.expires().isAfter(now))throw new IllegalStateException("CLAIM_LOST");
        if(candidates.size()!=1)return false;
        int changed=jdbc.update("with candidates as ("+BILLING_PAYMENT_CANDIDATES+"""
            ), owned as (select min(reference) as reference from candidates having count(*)=1)
            update provider_inbox i set event_family='SUBSCRIPTION',event_type='SUBSCRIPTION_CHANGED',
              provider_object_id=owned.reference,reference_object_type=null,provider_event_type=null,provider_api_version=null,
              status='PENDING',lease_owner=null,lease_expires_at=null,error_category=null,next_attempt_at=i.received_at
            from owned where i.id=? and i.event_family='PROVIDER_REFERENCE' and i.status='PROCESSING'
              and i.lease_owner=? and i.lease_expires_at>? and i.attempt_count between 1 and 12
              and i.provider_type='STRIPE' and i.reference_object_type='PAYMENT' and i.payment_account_id=?
              and i.provider_event_id=? and i.provider_object_id=? and i.provider_event_type=?
              and i.provider_api_version=? and i.occurred_at=? and i.received_at=? and owned.reference=?
            """,expected.accountId().value(),expected.objectId(),claim.id(),claim.owner(),Timestamp.from(now),
            expected.accountId().value(),expected.eventId(),expected.objectId(),expected.providerEventType(),expected.apiVersion(),
            Timestamp.from(expected.occurredAt()),Timestamp.from(expected.receivedAt()),candidates.getFirst());
        // Preserve the original admission hash, timestamps, safe payload and cumulative attempts.
        // Crossing the original expiry after UPDATE must roll the independent transaction back.
        if(changed!=1||!row.expires().isAfter(referenceClock.instant()))throw new IllegalStateException("CLAIM_LOST");
        return true;
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public ProviderReferenceReceipt lockReference(Claim claim) {
        return liveReference(claim).orElseThrow(() -> new IllegalStateException("CLAIM_LOST")).receipt();
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void completeReference(Claim claim) {
        liveReference(claim).orElseThrow(() -> new IllegalStateException("CLAIM_LOST"));
        jdbc.update("update provider_inbox set status='PROCESSED',lease_owner=null,lease_expires_at=null,error_category=null where id=?",claim.id());
    }

    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean failReference(Claim claim,String category) {
        if(category==null || !Set.of("UNSUPPORTED_EVENT","UNRESOLVED_OBJECT","IDENTITY_MISMATCH","PROCESSING_FAILED").contains(category))throw SafeFactJson.invalid();
        var row=liveReference(claim);if(row.isEmpty())return false;
        Instant now=referenceClock.instant();
        // Recheck in case a mutable test clock or a slow operation crossed expiry after the locked read.
        if(!row.get().expires().isAfter(now))return false;
        jdbc.update("""
            update provider_inbox set status=case when attempt_count>=12 then 'DEAD' else 'FAILED' end,
              lease_owner=null,lease_expires_at=null,error_category=?,
              next_attempt_at=?::timestamptz + least(300,5*(1 << least(greatest(attempt_count-1,0),6))) * interval '1 second' where id=?
            """,category,Timestamp.from(now),claim.id());
        return true;
    }

    private Optional<ReferenceRow> liveReference(Claim claim) {
        if(claim==null || claim.id()==null || claim.owner()==null)return Optional.empty();
        var rows=jdbc.query("select * from provider_inbox where event_family='PROVIDER_REFERENCE' and id=? for update",this::referenceRow,claim.id());
        // The time read MUST follow lock acquisition, including when another transaction held the row.
        Instant now=referenceClock.instant();
        return rows.stream().filter(row -> row.status().equals("PROCESSING") && claim.owner().equals(row.owner())
            && row.expires()!=null && row.expires().isAfter(now)).findFirst();
    }
    private record ReferenceRow(UUID id,ProviderReferenceReceipt receipt,String status,String owner,Instant expires,int attempts,Instant nextAttempt) {}
    private ReferenceRow referenceRow(ResultSet r,int n)throws SQLException {
        Timestamp expires=r.getTimestamp("lease_expires_at");
        return new ReferenceRow(r.getObject("id",UUID.class),new ProviderReferenceReceipt(PaymentProviderType.valueOf(r.getString("provider_type")),
            new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getString("provider_event_id"),r.getString("provider_object_id"),
            ProviderReferenceReceipt.ObjectType.valueOf(r.getString("reference_object_type")),r.getString("provider_event_type"),r.getString("provider_api_version"),
            r.getTimestamp("occurred_at").toInstant(),r.getTimestamp("received_at").toInstant()),r.getString("status"),r.getString("lease_owner"),
            expires==null?null:expires.toInstant(),r.getInt("attempt_count"),r.getTimestamp("next_attempt_at").toInstant());
    }
}
