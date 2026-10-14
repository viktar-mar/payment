package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.port.out.IdempotencyPort;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IdempotencyPersistenceAdapter implements IdempotencyPort,dev.genericpayments.platform.payments.application.port.out.IdempotencyLookupPort {
    private static final String COLUMNS = """
        id,scope,idempotency_key,fingerprint,status,resource_type,resource_id,response_status,
        response_secret_state,response_secret_ciphertext,response_secret_nonce,response_secret_key_version,
        response_secret_expires_at,expires_at,created_at,updated_at,version
        """;
    private final JdbcTemplate jdbc;
    public IdempotencyPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<IdempotencyRecord> find(String scope,String key) { return locked(scope,key).stream().findFirst(); }

    @Override @Transactional
    public Claim claim(IdempotencyRecord candidate, Instant now) {
        if (candidate.status() != Status.IN_PROGRESS || candidate.version() != 0)
            throw new IllegalArgumentException("Claim requires a new operation");
        // ON CONFLICT waits for a concurrent inserter without aborting this transaction.
        // A fresh READ COMMITTED SELECT then locks and sees the committed winning row.
        for (int retry=0; retry<3; retry++) {
            int inserted=jdbc.update("""
                insert into idempotency_records(id,scope,idempotency_key,fingerprint,status,expires_at,created_at,updated_at,version)
                values (?,?,?,?,'IN_PROGRESS',?,?,?,0) on conflict(scope,idempotency_key) do nothing
                """,candidate.id(),candidate.scope(),candidate.key(),candidate.fingerprint(),timestamp(candidate.expiresAt()),
                timestamp(candidate.createdAt()),timestamp(candidate.updatedAt()));
            if (inserted == 1) return new Claim(true,candidate);
            var rows=locked(candidate.scope(),candidate.key());
            if (rows.isEmpty()) continue; // Expired completed row was concurrently removed by cleanup.
            var record=rows.getFirst();
            if (record.fingerprint().equals(candidate.fingerprint()) && record.secretState() == SecretState.LIVE
                && !now.isBefore(record.secretExpiresAt())) {
                shred(record.id(),now);
                record=locked(candidate.scope(),candidate.key()).getFirst();
            }
            return new Claim(false,record);
        }
        throw new IllegalStateException("Idempotency claim could not be resolved");
    }
    @Override @Transactional
    public IdempotencyRecord complete(Ownership owner, Completion completion, Instant now) {
        var rows=locked(owner.scope(),owner.key());
        if (rows.isEmpty()) throw new IllegalStateException("Idempotency ownership is stale or mismatched");
        var updated=rows.getFirst().complete(owner,completion,now);
        EncryptedValue secret=updated.secret();
        int changed=jdbc.update("""
            update idempotency_records set status='COMPLETED',resource_type=?,resource_id=?,response_status=?,
              response_secret_state=?,response_secret_ciphertext=?,response_secret_nonce=?,response_secret_key_version=?,
              response_secret_expires_at=?,expires_at=?,updated_at=?,version=version+1
            where id=? and scope=? and idempotency_key=? and fingerprint=? and version=? and status='IN_PROGRESS'
            """,updated.resource().type().name(),updated.resource().id(),updated.responseStatus(),updated.secretState().name(),
            secret == null ? null : secret.ciphertext(),secret == null ? null : secret.nonce(),secret == null ? null : secret.keyVersion(),
            timestamp(updated.secretExpiresAt()),timestamp(updated.expiresAt()),timestamp(now),owner.id(),owner.scope(),owner.key(),owner.fingerprint(),owner.version());
        if (changed != 1) throw new IllegalStateException("Idempotency ownership is stale or mismatched");
        return updated;
    }
    private List<IdempotencyRecord> locked(String scope,String key) {
        return jdbc.query("select "+COLUMNS+" from idempotency_records where scope=? and idempotency_key=? FOR UPDATE",this::record,scope,key);
    }
    @Override @Transactional
    public void expireSecret(UUID id,Instant now) { shred(id,now); }
    private void shred(UUID id,Instant now) {
        jdbc.update("""
            update idempotency_records set response_secret_state='EXPIRED',response_secret_ciphertext=null,
              response_secret_nonce=null,response_secret_key_version=null,updated_at=?,version=version+1
            where id=? and response_secret_state='LIVE' and response_secret_expires_at<=?
            """,timestamp(now),id,timestamp(now));
    }
    @Override @Transactional
    public int cleanup(Instant now) {
        Objects.requireNonNull(now);
        jdbc.update("""
            update idempotency_records set response_secret_state='EXPIRED',response_secret_ciphertext=null,
              response_secret_nonce=null,response_secret_key_version=null,updated_at=?,version=version+1
            where response_secret_state='LIVE' and response_secret_expires_at<=?
            """,timestamp(now),timestamp(now));
        return jdbc.update("delete from idempotency_records where status='COMPLETED' and expires_at<=?",timestamp(now));
    }
    private IdempotencyRecord record(ResultSet row,int index) throws SQLException {
        SecretState secretState=SecretState.valueOf(row.getString("response_secret_state"));
        String type=row.getString("resource_type");
        return new IdempotencyRecord(row.getObject("id",UUID.class),row.getString("scope"),row.getString("idempotency_key"),
            row.getString("fingerprint"),Status.valueOf(row.getString("status")),
            type == null ? null : new Resource(ResourceType.valueOf(type),row.getObject("resource_id",UUID.class)),
            row.getObject("response_status",Integer.class),secretState,
            secretState == SecretState.LIVE ? new EncryptedValue(row.getBytes("response_secret_ciphertext"),row.getBytes("response_secret_nonce"),row.getString("response_secret_key_version")) : null,
            instant(row,"response_secret_expires_at"),instant(row,"expires_at"),instant(row,"created_at"),instant(row,"updated_at"),row.getLong("version"));
    }
    private static Instant instant(ResultSet row,String column) throws SQLException { Timestamp value=row.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
