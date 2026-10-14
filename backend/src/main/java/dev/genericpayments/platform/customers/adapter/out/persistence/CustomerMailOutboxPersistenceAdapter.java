package dev.genericpayments.platform.customers.adapter.out.persistence;

import static dev.genericpayments.platform.customers.adapter.out.persistence.PostgresTimestamps.canonical;

import dev.genericpayments.platform.customers.application.port.out.CustomerMailOutboxPort;
import dev.genericpayments.platform.customers.application.port.out.EmailOtpPolicyPort;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class CustomerMailOutboxPersistenceAdapter implements CustomerMailOutboxPort {
    private final JdbcTemplate jdbc; private final EmailOtpPolicyPort policy;
    public CustomerMailOutboxPersistenceAdapter(JdbcTemplate jdbc,EmailOtpPolicyPort policy){this.jdbc=jdbc;this.policy=policy;}

    @Override @Transactional
    public CustomerMailJob save(CustomerMailJob job,EncryptedValue payload) {
        int changed=jdbc.update("""
            insert into customer_mail_outbox(id,site_id,challenge_id,status,payload_ciphertext,payload_nonce,payload_key_version,
              attempt_count,next_attempt_at,lease_owner,lease_expires_at,error_category,created_at,updated_at,completed_at,payload_destroyed_at,version)
            values (?,?,?,'PENDING',?,?,?,0,?,null,null,null,?,?,null,null,0)
            """,job.id(),job.siteId().value(),job.challengeId().value(),payload.ciphertext(),payload.nonce(),payload.keyVersion(),
            timestamp(job.nextAttemptAt()),timestamp(job.createdAt()),timestamp(job.updatedAt()));
        if(changed!=1) throw new OptimisticLockingFailureException("Customer mail insert failed");
        return find(job.id()).orElseThrow().job();
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public List<Claim> claimReady(String owner,Instant now,int batchSize,Duration lease) {
        if(owner==null||owner.isBlank()||owner.length()>128) throw new IllegalArgumentException("Invalid mail worker owner");
        jdbc.update("""
            with exhausted as (
              select id from customer_mail_outbox
              where attempt_count>=? and ((status='PROCESSING' and lease_expires_at<=?)
                or (status='PENDING' and next_attempt_at<=?))
              order by next_attempt_at,created_at,id for update skip locked limit ?
            )
            update customer_mail_outbox set status='DEAD',payload_ciphertext=null,payload_nonce=null,payload_key_version=null,
              lease_owner=null,lease_expires_at=null,error_category='EXHAUSTED',updated_at=?,completed_at=?,payload_destroyed_at=?,version=version+1
            where id in (select id from exhausted)
            """,policy.mailMaxAttempts(),timestamp(now),timestamp(now),batchSize,timestamp(now),timestamp(now),timestamp(now));
        List<UUID> ids=jdbc.query("""
            select id from customer_mail_outbox
            where attempt_count<? and ((status='PENDING' and next_attempt_at<=?) or (status='PROCESSING' and lease_expires_at<=?))
            order by next_attempt_at,created_at,id for update skip locked limit ?
            """,(row,index)->row.getObject(1,UUID.class),policy.mailMaxAttempts(),timestamp(now),timestamp(now),batchSize);
        List<Claim> result=new ArrayList<>();
        for(UUID id:ids) {
            jdbc.update("""
                update customer_mail_outbox set status='PROCESSING',attempt_count=attempt_count+1,lease_owner=?,lease_expires_at=?,
                  error_category=null,updated_at=?,version=version+1 where id=?
                """,owner,timestamp(now.plus(lease)),timestamp(now),id);
            result.add(find(id).orElseThrow());
        }
        return List.copyOf(result);
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean renewLease(Claim claim,Instant renewedAt,Instant leaseExpiresAt) {
        return jdbc.update("""
            update customer_mail_outbox set lease_expires_at=?,updated_at=?
            where id=? and status='PROCESSING' and lease_owner=? and version=?
              and lease_expires_at>? and lease_expires_at<=?
            """,timestamp(leaseExpiresAt),timestamp(renewedAt),claim.id(),claim.owner(),claim.generation(),
            timestamp(renewedAt),timestamp(leaseExpiresAt))==1;
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean markSent(Claim claim,Instant now) {
        return jdbc.update("""
            update customer_mail_outbox set status='SENT',payload_ciphertext=null,payload_nonce=null,payload_key_version=null,
              lease_owner=null,lease_expires_at=null,error_category=null,updated_at=?,completed_at=?,payload_destroyed_at=?,version=version+1
            where id=? and status='PROCESSING' and lease_owner=? and version=? and lease_expires_at>?
            """,timestamp(now),timestamp(now),timestamp(now),claim.id(),claim.owner(),claim.generation(),timestamp(now))==1;
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean markFailure(Claim claim,CustomerMailJob.ErrorCategory category,Instant now,Instant nextAttemptAt) {
        CustomerMailJob claimed=claim.job();
        boolean terminal=category!=CustomerMailJob.ErrorCategory.TRANSIENT||claimed.attemptCount()>=policy.mailMaxAttempts();
        CustomerMailJob.ErrorCategory stored=claimed.attemptCount()>=policy.mailMaxAttempts()?CustomerMailJob.ErrorCategory.EXHAUSTED:category;
        if(terminal) return jdbc.update("""
            update customer_mail_outbox set status='DEAD',payload_ciphertext=null,payload_nonce=null,payload_key_version=null,
              lease_owner=null,lease_expires_at=null,error_category=?,updated_at=?,completed_at=?,payload_destroyed_at=?,version=version+1
            where id=? and status='PROCESSING' and lease_owner=? and version=? and lease_expires_at>?
            """,stored.name(),timestamp(now),timestamp(now),timestamp(now),claim.id(),claim.owner(),claim.generation(),timestamp(now))==1;
        return jdbc.update("""
            update customer_mail_outbox set status='PENDING',lease_owner=null,lease_expires_at=null,error_category=?,
              next_attempt_at=?,updated_at=?,version=version+1 where id=? and status='PROCESSING' and lease_owner=? and version=? and lease_expires_at>?
            """,stored.name(),timestamp(nextAttemptAt),timestamp(now),claim.id(),claim.owner(),claim.generation(),timestamp(now))==1;
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int destroyTerminalPayloads(Instant completedBefore,Instant now,int maximumRows) {
        return jdbc.update("""
            update customer_mail_outbox set payload_ciphertext=null,payload_nonce=null,payload_key_version=null,payload_destroyed_at=?,updated_at=?,version=version+1
            where id in (select id from customer_mail_outbox where status='DEAD' and payload_destroyed_at is null and completed_at<=? order by completed_at limit ?)
            """,timestamp(now),timestamp(now),timestamp(completedBefore),maximumRows);
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int deleteTerminalMetadata(Instant completedBefore,int maximumRows) {
        return jdbc.update("delete from customer_mail_outbox where id in (select id from customer_mail_outbox where status in ('SENT','DEAD') and payload_destroyed_at is not null and completed_at<=? order by completed_at limit ?)",
            timestamp(completedBefore),maximumRows);
    }
    private Optional<Claim> find(UUID id) {
        return jdbc.query("select * from customer_mail_outbox where id=?",(row,index)->claim(row),id).stream().findFirst();
    }
    private static Claim claim(ResultSet row) throws SQLException {
        var job=new CustomerMailJob(row.getObject("id",UUID.class),new SiteId(row.getObject("site_id",UUID.class)),
            new EmailOtpChallengeId(row.getObject("challenge_id",UUID.class)),CustomerMailJob.Status.valueOf(row.getString("status")),
            row.getInt("attempt_count"),canonical(row.getTimestamp("next_attempt_at").toInstant()),row.getString("lease_owner"),instant(row,"lease_expires_at"),
            row.getString("error_category")==null?null:CustomerMailJob.ErrorCategory.valueOf(row.getString("error_category")),
            canonical(row.getTimestamp("created_at").toInstant()),canonical(row.getTimestamp("updated_at").toInstant()),instant(row,"completed_at"),
            instant(row,"payload_destroyed_at"),row.getLong("version"));
        byte[] ciphertext=row.getBytes("payload_ciphertext");
        return new Claim(job,ciphertext==null?null:new EncryptedValue(ciphertext,row.getBytes("payload_nonce"),row.getString("payload_key_version")));
    }
    private static Instant instant(ResultSet row,String column)throws SQLException{Timestamp value=row.getTimestamp(column);return value==null?null:canonical(value.toInstant());}
    private static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(canonical(value));}
}
