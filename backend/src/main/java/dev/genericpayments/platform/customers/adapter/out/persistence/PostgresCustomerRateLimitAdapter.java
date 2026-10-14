package dev.genericpayments.platform.customers.adapter.out.persistence;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class PostgresCustomerRateLimitAdapter implements DistributedCustomerRateLimitPort {
    private final JdbcTemplate jdbc; private final EmailOtpPolicyPort policy;
    public PostgresCustomerRateLimitAdapter(JdbcTemplate jdbc,EmailOtpPolicyPort policy){this.jdbc=jdbc;this.policy=policy;}

    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void acquire(SiteId site,KeyedDigestPort.DigestValue siteDigest,KeyedDigestPort.DigestValue emailDigest,
            KeyedDigestPort.DigestValue sourceAddressDigest,Instant now) {
        Instant start=windowStart(now,policy.rateWindow()),expires=start.plus(policy.rateWindow());
        consume(site,"SITE",siteDigest,start,expires,policy.siteRequestLimit());
        consume(site,"EMAIL",emailDigest,start,expires,policy.emailRequestLimit());
        consume(site,"SOURCE_IP",sourceAddressDigest,start,expires,policy.sourceAddressRequestLimit());
    }
    private void consume(SiteId site,String type,KeyedDigestPort.DigestValue digest,Instant start,Instant expires,int limit) {
        int changed=jdbc.update("""
            insert into customer_rate_limit_buckets(id,site_id,bucket_type,bucket_digest,bucket_digest_key_version,
              window_started_at,expires_at,request_count,version) values (?,?,?,?,?,?,?,1,0)
            on conflict(site_id,bucket_type,bucket_digest,bucket_digest_key_version,window_started_at)
            do update set request_count=customer_rate_limit_buckets.request_count+1,version=customer_rate_limit_buckets.version+1
            where customer_rate_limit_buckets.request_count < ?
            """,UUID.randomUUID(),site.value(),type,digest.value(),digest.keyVersion(),Timestamp.from(start),Timestamp.from(expires),limit);
        if(changed!=1) throw new PaymentApplicationException("OTP_RATE_LIMITED",429,"OTP rate limit exceeded");
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int cleanupExpired(Instant now,int maximumRows) {
        return jdbc.update("delete from customer_rate_limit_buckets where id in (select id from customer_rate_limit_buckets where expires_at<=? order by expires_at limit ?)",
            Timestamp.from(now),maximumRows);
    }
    private static Instant windowStart(Instant value,Duration window) {
        long seconds=window.toSeconds(),epoch=value.getEpochSecond();
        return Instant.ofEpochSecond(Math.floorDiv(epoch,seconds)*seconds);
    }
}
