package dev.genericpayments.platform.customers.adapter.out.persistence;

import static dev.genericpayments.platform.customers.adapter.out.persistence.PostgresTimestamps.canonical;
import dev.genericpayments.platform.customers.application.port.out.CustomerIdempotencyStore;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional
public class CustomerIdempotencyPersistenceAdapter implements CustomerIdempotencyStore {
    private final JdbcTemplate jdbc;
    public CustomerIdempotencyPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Claim claim(Record candidate) {
        int inserted=jdbc.update("""
            insert into customer_idempotency_records(id,site_id,scope,idempotency_key,fingerprint,status,created_at,updated_at,expires_at,version)
            values (?,?,?,?,?,'IN_PROGRESS',?,?,?,0) on conflict(site_id,scope,idempotency_key) do nothing
            """,candidate.id(),candidate.siteId().value(),candidate.scope(),candidate.keyDigest(),candidate.fingerprint(),
            timestamp(candidate.createdAt()),timestamp(candidate.updatedAt()),timestamp(candidate.expiresAt()));
        Record stored=jdbc.query("select * from customer_idempotency_records where site_id=? and scope=? and idempotency_key=? for update",
            this::map,candidate.siteId().value(),candidate.scope(),candidate.keyDigest()).stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Customer idempotency claim disappeared"));
        return new Claim(inserted==1,stored);
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Record complete(UUID id,SiteId site,String scope,String keyDigest,long expectedVersion,UUID resourceId,int responseStatus,java.time.Instant now) {
        int updated=jdbc.update("""
            update customer_idempotency_records set status='COMPLETED',resource_id=?,response_status=?,updated_at=?,version=version+1
            where id=? and site_id=? and scope=? and idempotency_key=? and version=? and status='IN_PROGRESS'
            """,resourceId,responseStatus,timestamp(now),id,site.value(),scope,keyDigest,expectedVersion);
        if(updated!=1)throw new OptimisticLockingFailureException("Customer idempotency ownership was lost");
        return jdbc.query("select * from customer_idempotency_records where id=? and site_id=? and scope=? and idempotency_key=?",this::map,
            id,site.value(),scope,keyDigest).stream().findFirst().orElseThrow();
    }

    private Record map(ResultSet r,int row)throws SQLException {
        Timestamp completed=r.getTimestamp("updated_at");
        return new Record(r.getObject("id",UUID.class),new SiteId(r.getObject("site_id",UUID.class)),r.getString("scope"),r.getString("idempotency_key"),
            r.getString("fingerprint"),Status.valueOf(r.getString("status")),r.getObject("resource_id",UUID.class),
            (Integer)r.getObject("response_status"),canonical(r.getTimestamp("created_at").toInstant()),canonical(completed.toInstant()),
            canonical(r.getTimestamp("expires_at").toInstant()),r.getLong("version"));
    }
    private static Timestamp timestamp(java.time.Instant value){return Timestamp.from(canonical(value));}
}
