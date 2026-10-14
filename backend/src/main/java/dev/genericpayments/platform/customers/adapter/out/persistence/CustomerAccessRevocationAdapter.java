package dev.genericpayments.platform.customers.adapter.out.persistence;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class CustomerAccessRevocationAdapter implements CustomerAccessRevocationPort {
    private final JdbcTemplate jdbc;
    private final EncryptedSecretPort encryption;
    private final KeyedDigestPort digests;
    public CustomerAccessRevocationAdapter(JdbcTemplate jdbc,EncryptedSecretPort encryption,KeyedDigestPort digests) {
        this.jdbc=jdbc;this.encryption=encryption;this.digests=digests;
    }
    private record Alias(String issuer,String reference) {}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void revokeCustomerAccess(SiteId site,CustomerId customer,Instant now) {
        var time=Timestamp.from(PostgresTimestamps.canonical(now));
        var sessionIds=jdbc.queryForList("select id from customer_sessions where site_id=? and customer_id=? and revoked_at is null order by id for update",
            UUID.class,site.value(),customer.value());
        for(UUID id:sessionIds)jdbc.update("update customer_sessions set revoked_at=?,revoked_reason='ANONYMIZED',version=version+1 where site_id=? and id=?",
            time,site.value(),id);

        Set<Alias> aliases=new HashSet<>(jdbc.query("""
            select a.issuer_key,concat(a.subject_digest_key_version,':',a.subject_digest) as reference
              from customer_identity_subject_aliases a join customer_identities i on i.site_id=a.site_id and i.id=a.identity_id
             where i.site_id=? and i.customer_id=? and i.mode='EMAIL_OTP'
            """,(row,index)->new Alias(row.getString("issuer_key"),row.getString("reference")),site.value(),customer.value()));
        String profileEmail=jdbc.query("select email_ciphertext,email_nonce,email_key_version from customers where site_id=? and id=? and email_ciphertext is not null",
            (row,index)->encryption.decrypt(new EncryptedSecretPort.EncryptedValue(row.getBytes(1),row.getBytes(2),row.getString(3))),site.value(),customer.value())
            .stream().findFirst().orElse(null);
        // OTP lookup and subject digests have distinct HMAC purposes. Compare the encrypted challenge's
        // normalized email in memory; use every retained subject key when matching an EMAIL_OTP identity.
        var otpIds=jdbc.queryForList("select id from email_otp_challenges where site_id=? and consumed_at is null and invalidated_at is null and expires_at>? order by id for update",
            UUID.class,site.value(),time);
        for(UUID id:otpIds) {
            var match=jdbc.query("select provider_key,email_ciphertext,email_nonce,email_key_version from email_otp_challenges where site_id=? and id=?",
                (row,index) -> {
                    String email=encryption.decrypt(new EncryptedSecretPort.EncryptedValue(row.getBytes(2),row.getBytes(3),row.getString(4)));
                    String issuer=row.getString(1);
                    return email.equals(profileEmail)||digests.candidates(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,email).stream()
                        .anyMatch(digest -> aliases.contains(new Alias(issuer,digest.keyVersion()+":"+digest.value())));
                },site.value(),id).getFirst();
            if(match)jdbc.update("update email_otp_challenges set invalidated_at=?,version=version+1 where site_id=? and id=?",time,site.value(),id);
        }
        var proofIds=jdbc.queryForList("""
            select p.id from identity_link_proofs p where p.site_id=? and p.consumed_at is null and exists (
              select 1 from identity_link_proof_subject_aliases pa
                join customer_identity_subject_aliases ca on ca.site_id=pa.site_id and ca.issuer_key=p.issuer_key
                  and ca.subject_digest=pa.subject_digest and ca.subject_digest_key_version=pa.subject_digest_key_version
                join customer_identities i on i.site_id=ca.site_id and i.id=ca.identity_id
              where pa.site_id=p.site_id and pa.proof_id=p.id and i.customer_id=?)
            order by p.id for update of p
            """,UUID.class,site.value(),customer.value());
        for(UUID id:proofIds)jdbc.update("update identity_link_proofs set consumed_at=?,version=version+1 where site_id=? and id=?",time,site.value(),id);
    }
}
