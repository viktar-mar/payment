package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.model.CheckoutSession;
import dev.genericpayments.platform.payments.application.port.out.CheckoutSessionPort;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CheckoutSessionPersistenceAdapter implements CheckoutSessionPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public CheckoutSessionPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @Override @Transactional
    public void save(CheckoutSession session) {
        jdbc.update("""
            insert into checkout_sessions(id,session_type,payment_id,site_id,token_hash,origin,scopes,expires_at,created_at)
            values (?,'CHECKOUT',?,?,?,?,?::jsonb,?,?)
            """, session.id(), session.paymentId().value(), session.siteId().value(), session.tokenHash(), session.origin().toString(),
            mapper.writeValueAsString(session.scopes()), Timestamp.from(session.expiresAt()), Timestamp.from(session.createdAt()));
    }
    @Override @Transactional(readOnly = true)
    public Optional<CheckoutSession> findById(UUID id) {
        return jdbc.query("select * from checkout_sessions where id=? and session_type='CHECKOUT'", (row, index) ->
            new CheckoutSession(row.getObject("id", UUID.class), new PaymentId(row.getObject("payment_id", UUID.class)),
                new SiteId(row.getObject("site_id", UUID.class)), row.getString("token_hash"), URI.create(row.getString("origin")),
                mapper.readValue(row.getString("scopes"), new TypeReference<Set<String>>() {}), row.getTimestamp("expires_at").toInstant(),
                row.getTimestamp("created_at").toInstant()), id).stream().findFirst();
    }
}
