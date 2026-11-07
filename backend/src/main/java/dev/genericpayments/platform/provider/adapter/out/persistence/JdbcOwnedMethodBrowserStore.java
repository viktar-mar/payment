package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.*;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Caller-transaction companion. No bean, provider call, key material or ephemeral payload persistence. */
final class JdbcOwnedMethodBrowserStore {
    private final JdbcTemplate jdbc;private final JdbcOwnedFailedRenewalMethodStore methods;
    JdbcOwnedMethodBrowserStore(JdbcTemplate jdbc,JdbcOwnedFailedRenewalMethodStore methods){this.jdbc=Objects.requireNonNull(jdbc);this.methods=Objects.requireNonNull(methods);}
    Optional<Enrollment> replyEnrollment(Reservation r,CustomerSessionContext supplied,Instant now){
        transaction();if(r==null||now==null)throw unavailable();
        var fields=jdbc.queryForList("""
            select num_nonnulls(browser_session_id,browser_origin,browser_session_expires_at)
            from stripe_billing_failed_method_reservations where id=? and setup_operation=?
            """,Integer.class,r.id(),r.setupOperationId());
        if(fields.equals(List.of(0))){
            methods.requireReservationInTransaction(r);
            return Optional.empty();
        }
        if(!fields.equals(List.of(3)))throw unavailable();
        return Optional.of(setupEnrollment(r,supplied,now));
    }
    /** Original live enrollment independently of the ACK/proof-dependent browser pending phase. */
    Enrollment setupEnrollment(Reservation r,CustomerSessionContext session,Instant now){
        transaction();if(r==null||session==null||now==null)throw unavailable();methods.requireReservationInTransaction(r);
        jdbc.queryForList("select lock_owned_method_browser(?)",r.id());
        String scopes=session.scopes().stream().map(s->"\""+s.name()+"\"").sorted().collect(java.util.stream.Collectors.joining(",","[","]"));
        // Match V36 enrollment/pending identity and lifecycle semantics, without requiring the not-yet-created SETUP.
        // clock_timestamp is evaluated only after the operation/session/root locks above have completed.
        require("""
            select exists(select 1 from stripe_billing_failed_method_reservations r
            join stripe_billing_failed_renewals d on d.id=r.admission_id join subscription_operations o on o.id=r.setup_operation
            join customer_sessions c on c.id=r.browser_session_id
            join customers customer on customer.id=d.customer_id and customer.site_id=d.site_id
            join sites site on site.id=d.site_id join site_allowed_origins origin on origin.site_id=site.id and origin.origin=c.origin
            where r.id=? and owned_method_debt(r.id,0,o.id) and owned_method_initial_operation(r.id)
            and r.request_fingerprint=owned_method_browser_request(d.id,r.browser_origin)
            and row(c.site_id,c.customer_id,c.origin,c.expires_at)=row(d.site_id,d.customer_id,r.browser_origin,r.browser_session_expires_at)
            and row(c.id,c.site_id,c.customer_id,c.origin,c.expires_at)=row(?::uuid,?::uuid,?::uuid,?::text,?::timestamptz)
            and c.scopes @> ?::jsonb and c.scopes <@ ?::jsonb and c.scopes @> '["SUBSCRIPTION_MANAGE"]'::jsonb
            and c.revoked_at is null and c.issued_at<=r.reserved_at and customer.status='ACTIVE' and site.status='ACTIVE'
            and ?<=clock_timestamp() and ?<r.browser_session_expires_at and clock_timestamp()<r.browser_session_expires_at)
            """,r.id(),session.sessionId().value(),session.siteId().value(),session.customerId().value(),session.origin(),time(session.expiresAt()),
            scopes,scopes,time(now),time(now));
        var enrollment=jdbc.query("select browser_session_id,browser_origin,browser_session_expires_at from stripe_billing_failed_method_reservations where id=?",
            (row,n)->new Enrollment(uuid(row,"browser_session_id"),row.getString("browser_origin"),instant(row,"browser_session_expires_at")),r.id())
            .stream().findFirst().orElseThrow(JdbcOwnedMethodBrowserStore::unavailable);
        // JDBC timestamp normalization must not turn an inexact caller expiry into the original Instant.
        if(!enrollment.sessionExpiresAt().equals(session.expiresAt()))throw unavailable();
        return enrollment;
    }
    Pending pending(Reservation r,CustomerSessionContext session,Instant now){
        transaction();if(r==null||session==null)throw unavailable();
        var p=pending(r,now);
        if(!p.enrollment().sessionId().equals(session.sessionId().value())||!p.enrollment().origin().equals(session.origin())
            ||!p.enrollment().sessionExpiresAt().equals(session.expiresAt())||!r.binding().siteId().equals(session.siteId())||!r.binding().customerId().equals(session.customerId()))throw unavailable();
        String scopes=session.scopes().stream().map(s->"\""+s.name()+"\"").sorted().collect(java.util.stream.Collectors.joining(",","[","]"));
        require("select scopes @> ?::jsonb and scopes <@ ?::jsonb from customer_sessions where id=?",scopes,scopes,session.sessionId().value());
        return p;
    }
    private Pending pending(Reservation r,Instant now){
        transaction();if(r==null||now==null)throw unavailable();methods.requireReservationInTransaction(r);
        jdbc.queryForList("select lock_owned_method_browser(?)",r.id());
        require("select owned_method_browser_pending(?)",r.id());
        var enrollment=jdbc.query("select browser_session_id,browser_origin,browser_session_expires_at from stripe_billing_failed_method_reservations where id=?",
            (row,n)->new Enrollment(uuid(row,"browser_session_id"),row.getString("browser_origin"),instant(row,"browser_session_expires_at")),r.id())
            .stream().findFirst().orElseThrow(JdbcOwnedMethodBrowserStore::unavailable);
        require("select ?<=clock_timestamp() and ?<browser_session_expires_at and clock_timestamp()<browser_session_expires_at from stripe_billing_failed_method_reservations where id=?",
            time(now),time(now),r.id());
        var setup=methods.setup(r);
        return new Pending(r,enrollment,methods.account(r),methods.subscription(r),methods.operation(r,r.setupOperationId()),r.binding(),setup,
            JdbcProviderOperationStore.row(jdbc,setup.step()).orElseThrow(JdbcOwnedMethodBrowserStore::unavailable));
    }
    Optional<Root> root(UUID reservation){
        transaction();if(reservation==null)throw unavailable();
        jdbc.queryForList("select lock_owned_method_browser(?)",reservation);
        return jdbc.query("select * from stripe_billing_method_browser_roots where reservation_id=? for update",this::rootRow,reservation).stream().findFirst();
    }
    Root insert(Pending p,Root proposed){
        transaction();if(p==null||proposed==null)throw unavailable();validate(p,proposed.issuedAt());
        if(!p.reservation().id().equals(proposed.reservationId())||!p.operation().id().equals(proposed.setupOperation())||!p.setupStep().id().equals(proposed.setupStep())
            ||p.setupStep().version()!=proposed.stepVersion()||!p.setup().reference().equals(proposed.setupReference()))throw unavailable();
        if(jdbc.update("""
            insert into stripe_billing_method_browser_roots(id,reservation_id,setup_operation,setup_step,step_version,payment_account_id,
             setup_reference,key_version,digest,issued_at,expires_at) values(?,?,?,?,?,?,?,?,?,?,?)
            """,proposed.id(),proposed.reservationId(),proposed.setupOperation(),proposed.setupStep(),proposed.stepVersion(),p.account().id().value(),
            proposed.setupReference(),proposed.keyVersion(),proposed.digest(),time(proposed.issuedAt()),time(proposed.expiresAt()))!=1)throw unavailable();
        var saved=root(proposed.reservationId()).orElseThrow(JdbcOwnedMethodBrowserStore::unavailable);if(!saved.equals(proposed))throw unavailable();return saved;
    }
    void action(Pending p,Root root,Instant now){
        transaction();locked(p,root,now);require("select not exists(select 1 from stripe_billing_method_browser_selections where root_id=?)",root.id());
    }
    Selection start(Pending p,Root root,String key,Instant now){
        transaction();action(p,root,now);
        var at=utc(now);var limit=at.plusSeconds(30);if(root.expiresAt().isBefore(limit))limit=root.expiresAt();
        var selected=new Selection(root.id(),key,at,limit);
        if(jdbc.update("insert into stripe_billing_method_browser_selections(root_id,key_digest,started_at,deadline_at) values(?,?,?,?)",
            root.id(),key,time(at),time(limit))!=1)throw unavailable();return selected;
    }
    void finish(Pending p,Root root,Selection selected,Instant now){
        transaction();locked(p,root,now);if(selected==null||!root.id().equals(selected.rootId()))throw unavailable();
        var original=jdbc.query("select * from stripe_billing_method_browser_selections where root_id=?",(row,n)->new Selection(root.id(),row.getString("key_digest"),instant(row,"started_at"),instant(row,"deadline_at")),root.id());
        if(original.size()!=1||!selected.equals(original.getFirst()))throw unavailable();
        if(jdbc.update("insert into stripe_billing_method_browser_releases(root_id,released_at) values(?,?)",root.id(),time(now))!=1)throw unavailable();
    }
    boolean consume(Pending p,Root root,String key,String fingerprint,Instant now){
        transaction();if(key==null||!key.matches("[a-f0-9]{64}")||fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw unavailable();
        locked(p,root,now);
        var previous=jdbc.query("select key_digest,request_fingerprint from stripe_billing_method_browser_consumptions where root_id=?",
            (row,n)->List.of(row.getString("key_digest"),row.getString("request_fingerprint")),root.id());
        if(!previous.isEmpty()){if(!previous.getFirst().equals(List.of(key,fingerprint)))throw unavailable();return false;}
        if(jdbc.update("insert into stripe_billing_method_browser_consumptions(root_id,key_digest,request_fingerprint,consumed_at) values(?,?,?,?)",
            root.id(),key,fingerprint,time(now))!=1)throw unavailable();return true;
    }
    private void validate(Pending p,Instant now){if(p==null||!pending(p.reservation(),now).equals(p))throw unavailable();}
    private void locked(Pending p,Root expected,Instant now){
        if(p==null||expected==null)throw unavailable();validate(p,now);
        if(!p.reservation().id().equals(expected.reservationId())||!root(p.reservation().id()).filter(expected::equals).isPresent())throw unavailable();
        // Includes fresh DB time after the blocking root lock; caller timestamps alone never authorize expiry.
        require("select ?>=issued_at and ?<expires_at and ?<=clock_timestamp() and clock_timestamp()<expires_at from stripe_billing_method_browser_roots where id=?",
            time(now),time(now),time(now),expected.id());
    }
    private Root rootRow(ResultSet row,int n)throws SQLException{return new Root(uuid(row,"id"),uuid(row,"reservation_id"),uuid(row,"setup_operation"),uuid(row,"setup_step"),row.getLong("step_version"),row.getString("setup_reference"),row.getString("key_version"),row.getString("digest"),instant(row,"issued_at"),instant(row,"expires_at"));}
    private void require(String sql,Object... args){if(!Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)))throw unavailable();}
    private static UUID uuid(ResultSet row,String field)throws SQLException{return row.getObject(field,UUID.class);}
    private static Instant instant(ResultSet row,String field)throws SQLException{return row.getTimestamp(field).toInstant();}
    private static Instant utc(Instant at){if(at==null)throw unavailable();return at.truncatedTo(ChronoUnit.MICROS);}
    private static Timestamp time(Instant at){return Timestamp.from(utc(at));}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Owned method transition requires caller transaction");}
    private static IllegalStateException unavailable(){return new IllegalStateException("Owned method browser authority unavailable");}
}
