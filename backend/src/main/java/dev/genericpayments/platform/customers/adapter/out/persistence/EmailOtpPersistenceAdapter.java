package dev.genericpayments.platform.customers.adapter.out.persistence;

import static dev.genericpayments.platform.customers.adapter.out.persistence.CustomerPersistenceMapper.Digest;
import static dev.genericpayments.platform.customers.adapter.out.persistence.PostgresTimestamps.canonical;

import dev.genericpayments.platform.customers.application.port.out.EmailOtpChallengeStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional
public class EmailOtpPersistenceAdapter implements EmailOtpChallengeStore {
    private final JdbcTemplate jdbc;
    public EmailOtpPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<EmailOtpChallenge> findForUpdate(SiteId site,EmailOtpChallengeId id) {
        return jdbc.query("select * from email_otp_challenges where site_id=? and id=? for update",
            (row,index)->domain(row),site.value(),id.value()).stream().findFirst();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<EmailOtpChallenge> findActiveForUpdate(SiteId site,List<String> emailDigests,String origin) {
        if(emailDigests==null||emailDigests.isEmpty()) return List.of();
        var references=emailDigests.stream().map(Digest::parse).map(Digest::reference).distinct().sorted().toList();
        String placeholders=String.join(",",java.util.Collections.nCopies(references.size(),"?"));
        var arguments=new ArrayList<Object>(); arguments.add(site.value()); arguments.add(origin); arguments.addAll(references);
        return jdbc.query("""
            select * from email_otp_challenges where site_id=? and origin=?
              and concat(email_digest_key_version,':',email_digest) in (%s)
              and consumed_at is null and invalidated_at is null
            order by created_at desc,id desc for update
            """.formatted(placeholders),(row,index)->domain(row),arguments.toArray());
    }
    @Override public EmailOtpChallenge save(EmailOtpChallenge value) { return saveInternal(value,null); }
    @Override public EmailOtpChallenge save(EmailOtpChallenge value,EncryptedValue email) { return saveInternal(value,Objects.requireNonNull(email)); }
    private EmailOtpChallenge saveInternal(EmailOtpChallenge value,EncryptedValue email) {
        Digest emailDigest=Digest.parse(value.emailDigest()),otpDigest=Digest.parse(value.otpDigest());
        int changed;
        if(email!=null) {
            if(value.version()!=0||!value.encryptedEmailRef().equals(EmailOtpChallengeStore.emailReference(value.id())))
                throw new IllegalArgumentException("Invalid new OTP challenge");
            changed=jdbc.update("""
                insert into email_otp_challenges(id,site_id,origin,intent,provider_key,email_digest,email_digest_key_version,
                  email_ciphertext,email_nonce,email_key_version,otp_digest,otp_digest_key_version,attempt_count,
                  previous_challenge_id,created_at,expires_at,consumed_at,invalidated_at,version)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                """,value.id().value(),value.siteId().value(),value.origin(),value.intent().name(),value.providerKey(),emailDigest.value(),emailDigest.keyVersion(),
                email.ciphertext(),email.nonce(),email.keyVersion(),otpDigest.value(),otpDigest.keyVersion(),value.attemptCount(),
                value.previousChallengeId().map(EmailOtpChallengeId::value).orElse(null),Timestamp.from(canonical(value.createdAt())),
                Timestamp.from(canonical(value.expiresAt())),timestamp(value.consumedAt().orElse(null)),timestamp(value.invalidatedAt().orElse(null)));
        } else {
            changed=jdbc.update("""
                update email_otp_challenges set attempt_count=?,consumed_at=?,invalidated_at=?,version=version+1
                where site_id=? and id=? and version=?
                """,value.attemptCount(),timestamp(value.consumedAt().orElse(null)),timestamp(value.invalidatedAt().orElse(null)),
                value.siteId().value(),value.id().value(),value.version());
        }
        if(changed!=1) throw new OptimisticLockingFailureException("Stale email OTP challenge");
        return query(value.siteId(),value.id()).orElseThrow();
    }
    @Override @Transactional(readOnly=true)
    public Optional<EncryptedValue> encryptedEmail(SiteId site,EmailOtpChallengeId id) {
        return jdbc.query("select email_ciphertext,email_nonce,email_key_version from email_otp_challenges where site_id=? and id=?",
            (row,index)->new EncryptedValue(row.getBytes(1),row.getBytes(2),row.getString(3)),site.value(),id.value()).stream().findFirst();
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int deleteExpiredWithoutOutboxReferences(java.time.Instant expiredBefore,int maximumRows) {
        return jdbc.update("""
            delete from email_otp_challenges where id in (
              select challenge.id from email_otp_challenges challenge
              where challenge.expires_at<=? and not exists (
                select 1 from customer_mail_outbox outbox where outbox.challenge_id=challenge.id)
              order by challenge.expires_at,challenge.id for update skip locked limit ?)
            """,Timestamp.from(canonical(expiredBefore)),maximumRows);
    }
    private Optional<EmailOtpChallenge> query(SiteId site,EmailOtpChallengeId id) {
        return jdbc.query("select * from email_otp_challenges where site_id=? and id=?",(row,index)->domain(row),site.value(),id.value()).stream().findFirst();
    }
    private static EmailOtpChallenge domain(ResultSet row) throws SQLException {
        Timestamp consumed=row.getTimestamp("consumed_at"),invalidated=row.getTimestamp("invalidated_at");
        UUID previous=row.getObject("previous_challenge_id",UUID.class);
        var id=new EmailOtpChallengeId(row.getObject("id",UUID.class));
        return EmailOtpChallenge.reconstitute(id,new SiteId(row.getObject("site_id",UUID.class)),row.getString("origin"),
            AuthenticationIntent.valueOf(row.getString("intent")),row.getString("provider_key"),new Digest(row.getString("email_digest"),row.getString("email_digest_key_version")).reference(),
            EmailOtpChallengeStore.emailReference(id),new Digest(row.getString("otp_digest"),row.getString("otp_digest_key_version")).reference(),row.getInt("attempt_count"),
            previous==null?null:new EmailOtpChallengeId(previous),canonical(row.getTimestamp("created_at").toInstant()),
            canonical(row.getTimestamp("expires_at").toInstant()),consumed==null?null:canonical(consumed.toInstant()),
            invalidated==null?null:canonical(invalidated.toInstant()),row.getLong("version"));
    }
    private static Timestamp timestamp(java.time.Instant value){return value==null?null:Timestamp.from(canonical(value));}
}
