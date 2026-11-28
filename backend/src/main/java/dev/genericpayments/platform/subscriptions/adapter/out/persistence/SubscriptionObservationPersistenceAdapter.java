package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObservationPort;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
public class SubscriptionObservationPersistenceAdapter implements SubscriptionObservationPort {
    private final JdbcTemplate jdbc;
    public SubscriptionObservationPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(readOnly=true)
    public long captureFence(Subscription s){
        return jdbc.query("select observation_sequence from subscriptions where id=? and site_id=? and payment_account_id=? and revision=?",
            (r,n)->r.getLong(1),s.id(),s.siteId().value(),s.paymentAccountId().value(),s.revision()).stream().findFirst()
            .orElseThrow(()->new IllegalStateException("OBSERVATION_STALE"));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void consumeFence(Subscription s,long sequence){
        if(sequence<0||jdbc.update("""
            update subscriptions set observation_sequence=observation_sequence+1
             where id=? and site_id=? and payment_account_id=? and revision=? and observation_sequence=?
               and observation_sequence<9223372036854775807
            """,s.id(),s.siteId().value(),s.paymentAccountId().value(),s.revision(),sequence)!=1)
            throw new IllegalStateException("OBSERVATION_STALE");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean acceptRevision(Subscription s,long revision){
        if(revision<0)throw new IllegalArgumentException("Invalid observation revision");
        var stored=jdbc.queryForObject("select observed_provider_revision from subscriptions where id=? and site_id=? and payment_account_id=? for update",Long.class,s.id(),s.siteId().value(),s.paymentAccountId().value());
        if(stored!=null&&revision<stored)return false;
        if(stored==null||revision>stored)jdbc.update("update subscriptions set observed_provider_revision=? where id=? and site_id=? and payment_account_id=?",revision,s.id(),s.siteId().value(),s.paymentAccountId().value());
        return true;
    }
}
