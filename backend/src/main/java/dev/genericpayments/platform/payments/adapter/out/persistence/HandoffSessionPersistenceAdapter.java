package dev.genericpayments.platform.payments.adapter.out.persistence;
import dev.genericpayments.platform.payments.application.model.HandoffSession;
import dev.genericpayments.platform.payments.application.port.out.HandoffSessionPort;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
public class HandoffSessionPersistenceAdapter implements HandoffSessionPort {
    private final JdbcTemplate jdbc;
    public HandoffSessionPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override @Transactional public void save(HandoffSession session) {
        jdbc.update("""
            insert into checkout_sessions(id,session_type,payment_id,site_id,token_hash,origin,scopes,expires_at,created_at,consumed_at)
            values (?,'HANDOFF',?,?,?,?,'[]'::jsonb,?,?,?)
            """,session.id(),session.paymentId().value(),session.siteId().value(),session.tokenHash(),session.origin().toString(),Timestamp.from(session.expiresAt()),
            Timestamp.from(session.createdAt()),session.consumedAt()==null ? null : Timestamp.from(session.consumedAt()));
    }
    @Override @Transactional(readOnly=true) public Optional<HandoffSession> findById(UUID id) {
        return jdbc.query("select * from checkout_sessions where id=? and session_type='HANDOFF'",this::map,id).stream().findFirst();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY) public Optional<HandoffSession> lockByHash(String hash) {
        return jdbc.query("select * from checkout_sessions where token_hash=? and session_type='HANDOFF' for update",this::map,hash).stream().findFirst();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY) public void consume(UUID id,Instant now) {
        if (jdbc.update("update checkout_sessions set consumed_at=? where id=? and session_type='HANDOFF' and consumed_at is null",Timestamp.from(now),id)!=1) throw new IllegalStateException("Handoff already consumed");
    }
    private HandoffSession map(ResultSet row,int index) throws SQLException {
        var consumed=row.getTimestamp("consumed_at");
        return new HandoffSession(row.getObject("id",UUID.class),new PaymentId(row.getObject("payment_id",UUID.class)),new SiteId(row.getObject("site_id",UUID.class)),
            row.getString("token_hash"),URI.create(row.getString("origin")),row.getTimestamp("expires_at").toInstant(),row.getTimestamp("created_at").toInstant(),consumed==null ? null : consumed.toInstant());
    }
}
