package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import static dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionPersistenceValues.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.dao.OptimisticLockingFailureException;

@Repository @Transactional
public class SubscriptionOperationPersistenceAdapter implements SubscriptionOperationStore {
    private final JdbcTemplate jdbc;
    public SubscriptionOperationPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public Claim claim(SubscriptionOperation op){
        if(op.version()!=0||op.status()!=SubscriptionOperation.Status.RESERVED)throw new IllegalArgumentException("New claim must be reserved at version zero");
        int inserted=insert(jdbc,"subscription_operations",values(op),"on conflict(site_id,authority_scope,kind,key_digest) do nothing");
        var stored=jdbc.query("select * from subscription_operations where site_id=? and authority_scope=? and kind=? and key_digest=? for update",this::map,op.siteId().value(),op.authorityScope(),op.kind().name(),op.keyDigest()).getFirst();
        return new Claim(stored,inserted==1);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public SubscriptionOperation lock(UUID id){return jdbc.query("select * from subscription_operations where id=? for update",this::map,id).stream().findFirst().orElseThrow();}
    public Optional<SubscriptionOperation> findByRequest(SiteId site,String authorityScope,SubscriptionOperation.Kind kind,String keyDigest){return jdbc.query("select * from subscription_operations where site_id=? and authority_scope=? and kind=? and key_digest=?",this::map,site.value(),authorityScope,kind.name(),keyDigest).stream().findFirst();}
    public SubscriptionOperation save(SubscriptionOperation op,long expected){
        if(expected<0||op.version()!=Math.incrementExact(expected))throw new OptimisticLockingFailureException("Invalid operation version");
        var v=values(op);
        if(update(jdbc,"subscription_operations",v,"id=:id and site_id=:site_id and customer_id=:customer_id and kind=:kind and authority_scope=:authority_scope and key_digest=:key_digest and fingerprint=:fingerprint and provider_operation_key=:provider_operation_key and created_at=:created_at and (subscription_id is null or subscription_id=:subscription_id) and version=:expected",Map.of("expected",expected))!=1)throw new OptimisticLockingFailureException("Operation changed");
        return lock(op.id());
    }
    public boolean hasUnresolved(SiteId site,UUID subscription){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscription_operations where site_id=? and subscription_id=? and status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and kind not in ('PORTAL','RECONCILE'))",Boolean.class,site.value(),subscription));}
    public Optional<SubscriptionOperation> find(SiteId site,UUID subscription,UUID operation){return jdbc.query("select * from subscription_operations where site_id=? and subscription_id=? and id=?",this::map,site.value(),subscription,operation).stream().findFirst();}
    public List<SubscriptionOperation> unresolved(SiteId site,UUID subscription){return jdbc.query("select * from subscription_operations where site_id=? and subscription_id=? and status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') order by id",this::map,site.value(),subscription);}
    private Map<String,Object> values(SubscriptionOperation o){
        var v=new LinkedHashMap<String,Object>();v.put("id",o.id());v.put("site_id",o.siteId().value());v.put("customer_id",o.customerId().value());v.put("subscription_id",o.subscriptionId());v.put("kind",o.kind().name());v.put("status",o.status().name());v.put("authority_scope",o.authorityScope());v.put("key_digest",o.keyDigest());v.put("fingerprint",o.fingerprint());v.put("provider_operation_key",o.providerOperationKey());v.put("expected_revision",o.expectedRevision());v.put("policy_version",o.policyVersion());v.put("provider_reference",o.providerReference());v.put("safe_failure_code",o.safeFailureCode());v.put("created_at",time(o.createdAt()));v.put("updated_at",time(o.updatedAt()));v.put("version",o.version());return v;
    }
    SubscriptionOperation map(ResultSet r,int row)throws SQLException{return new SubscriptionOperation(uuid(r,"id"),new SiteId(uuid(r,"site_id")),new CustomerId(uuid(r,"customer_id")),uuid(r,"subscription_id"),SubscriptionOperation.Kind.valueOf(r.getString("kind")),SubscriptionOperation.Status.valueOf(r.getString("status")),r.getString("authority_scope"),r.getString("key_digest"),r.getString("fingerprint"),r.getString("provider_operation_key"),r.getLong("expected_revision"),r.getLong("policy_version"),r.getString("provider_reference"),r.getString("safe_failure_code"),instant(r,"created_at"),instant(r,"updated_at"),r.getLong("version"));}
}
