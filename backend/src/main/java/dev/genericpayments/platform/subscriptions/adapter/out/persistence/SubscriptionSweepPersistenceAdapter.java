package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSweepPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository @Transactional
public class SubscriptionSweepPersistenceAdapter implements SubscriptionSweepPort {
    private final JdbcTemplate jdbc;private final Clock clock;
    public SubscriptionSweepPersistenceAdapter(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public List<Claim> claim(String owner,int batch,Instant now,Duration lease){
        if(owner==null||owner.isBlank()||owner.length()>255||batch<1||batch>100||lease.compareTo(Duration.ofSeconds(1))<0||lease.compareTo(Duration.ofSeconds(600))>0)throw new IllegalArgumentException("Invalid sweep claim");
        var candidates=jdbc.query("""
            select s.id,s.site_id,s.revision from subscriptions s
             where next_reconcile_at<=? and (reconcile_lease_until is null or reconcile_lease_until<=?)
             order by exists(select 1 from subscription_operations o where o.subscription_id=s.id and o.kind not in ('RECONCILE','PORTAL') and o.status in ('UNKNOWN','PROCESSING','REQUIRES_ACTION','RESERVED')) desc,
             next_reconcile_at,id limit ? for update of s skip locked
            """,(r,n)->new Claim(new SiteId(r.getObject("site_id",UUID.class)),r.getObject("id",UUID.class),owner,UUID.randomUUID(),r.getLong("revision")),Timestamp.from(now),Timestamp.from(now),batch);
        for(var c:candidates)jdbc.update("update subscriptions set reconcile_lease_owner=?,reconcile_lease_token=?,reconcile_lease_until=?,reconcile_lease_revision=? where id=?",owner,c.leaseToken(),Timestamp.from(now.plus(lease)),c.revision(),c.subscriptionId());
        return candidates;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireCurrent(Claim c,Instant now){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscriptions where id=? and site_id=? and reconcile_lease_owner=? and reconcile_lease_token=? and reconcile_lease_revision=? and revision=? and reconcile_lease_until>?)",Boolean.class,c.subscriptionId(),c.siteId().value(),c.owner(),c.leaseToken(),c.revision(),c.revision(),Timestamp.from(now))))throw new IllegalStateException("CLAIM_LOST");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Claim advanceRevision(Claim c,long revision,Instant now){
        if(jdbc.update("update subscriptions set reconcile_lease_revision=? where id=? and site_id=? and reconcile_lease_owner=? and reconcile_lease_token=? and reconcile_lease_revision=? and revision=? and reconcile_lease_until>?",revision,c.subscriptionId(),c.siteId().value(),c.owner(),c.leaseToken(),c.revision(),revision,Timestamp.from(now))!=1)throw new IllegalStateException("CLAIM_LOST");
        return new Claim(c.siteId(),c.subscriptionId(),c.owner(),c.leaseToken(),revision);
    }
    public void finish(Claim c,Instant nextCheck,boolean succeeded){
        jdbc.update("""
            update subscriptions set next_reconcile_at=?,last_reconciled_at=case when ? then ? else last_reconciled_at end,
             reconcile_attempts=case when ? then 0 else least(reconcile_attempts+1,4) end,
             reconcile_lease_owner=null,reconcile_lease_token=null,reconcile_lease_until=null,reconcile_lease_revision=null
             where id=? and site_id=? and reconcile_lease_owner=? and reconcile_lease_token=? and reconcile_lease_revision=? and revision=? and reconcile_lease_until>?
            """,Timestamp.from(nextCheck),succeeded,Timestamp.from(clock.instant()),succeeded,c.subscriptionId(),c.siteId().value(),c.owner(),c.leaseToken(),c.revision(),c.revision(),Timestamp.from(clock.instant()));
    }
    public int attempts(Claim c){return jdbc.queryForObject("select reconcile_attempts from subscriptions where site_id=? and id=?",Integer.class,c.siteId().value(),c.subscriptionId());}
    public List<Claim> expiredGrace(int batch,Instant now){
        if(batch<1||batch>100)throw new IllegalArgumentException("Invalid deadline batch");
        return jdbc.query("""
            select s.id,s.site_id,s.revision from subscriptions s where billing_status='PAST_DUE' and grace_deadline<=?
             and not exists(select 1 from subscription_events e where e.subscription_id=s.id and e.fact_key='grace-expired:'||s.debt_billing_record_id::text)
             order by grace_deadline,id limit ?
            """,(r,n)->new Claim(new SiteId(r.getObject("site_id",UUID.class)),r.getObject("id",UUID.class),"deadline",null,r.getLong("revision")),Timestamp.from(now),batch);
    }
}
