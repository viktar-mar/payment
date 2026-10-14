package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import static dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionPersistenceValues.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionQuoteStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class SubscriptionQuotePersistenceAdapter implements SubscriptionQuoteStore {
    private final JdbcTemplate jdbc;private final Clock clock;
    public SubscriptionQuotePersistenceAdapter(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public void save(SubscriptionQuote q){
        var v=new LinkedHashMap<String,Object>();v.put("id",q.id());v.put("site_id",q.siteId().value());v.put("subscription_id",q.subscriptionId());
        v.put("customer_id",jdbc.queryForObject("select customer_id from subscriptions where site_id=? and id=?",UUID.class,q.siteId().value(),q.subscriptionId()));
        v.put("product_id",q.target().productId().value());v.put("authority_scope",q.authorityScope());v.put("from_price_id",q.fromPriceId().value());v.put("from_price_version",q.fromPriceVersion());
        v.put("currency",q.currency());terms(v,"target",q.target());v.put("timing",q.timing().name());v.put("due_now_minor",q.dueNowMinor());v.put("next_amount_minor",q.nextAmountMinor());
        v.put("effective_at",time(q.effectiveAt()));v.put("expires_at",time(q.expiresAt()));v.put("created_at",time(clock.instant()));v.put("tax_final",q.taxFinal());
        v.put("subscription_revision",q.subscriptionRevision());v.put("policy_version",q.policyVersion());v.put("provider_quote_reference",q.providerQuoteReference());
        insert(jdbc,"subscription_quotes",v,"");
    }
    public SubscriptionQuote lock(SiteId site,UUID id){return jdbc.query("select * from subscription_quotes where site_id=? and id=? for update",this::map,site.value(),id).stream().findFirst().orElseThrow(()->new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.STATE_CONFLICT,"Quote unavailable"));}
    public void bind(SubscriptionOperation o,SubscriptionQuote q){
        if((o.kind()!=SubscriptionOperation.Kind.CHANGE&&o.kind()!=SubscriptionOperation.Kind.CLEAR_CHANGE)||!o.siteId().equals(q.siteId())||!o.subscriptionId().equals(q.subscriptionId()))throw new IllegalArgumentException("Quote ownership mismatch");
        int inserted=jdbc.update("""
            insert into subscription_change_intents(operation_id,site_id,subscription_id,quote_id)
            select o.id,o.site_id,o.subscription_id,q.id from subscription_operations o join subscription_quotes q
              on q.site_id=o.site_id and q.subscription_id=o.subscription_id and q.customer_id=o.customer_id
            where o.id=? and o.site_id=? and o.subscription_id=? and q.id=? and o.kind=?
            on conflict(operation_id) do nothing
            """,o.id(),o.siteId().value(),o.subscriptionId(),q.id(),o.kind().name());
        if(inserted==0&&!forOperation(o.siteId(),o.subscriptionId(),o.id()).map(q::equals).orElse(false))throw new IllegalArgumentException("Conflicting quote intent");
    }
    public Optional<SubscriptionQuote> forOperation(SiteId site,UUID subscription,UUID operation){return jdbc.query("select q.* from subscription_quotes q join subscription_change_intents i on i.quote_id=q.id and i.site_id=q.site_id and i.subscription_id=q.subscription_id where i.site_id=? and i.subscription_id=? and i.operation_id=?",this::map,site.value(),subscription,operation).stream().findFirst();}
    private SubscriptionQuote map(ResultSet r,int row)throws SQLException{return new SubscriptionQuote(uuid(r,"id"),new SiteId(uuid(r,"site_id")),uuid(r,"subscription_id"),r.getString("authority_scope"),new PriceId(uuid(r,"from_price_id")),r.getInt("from_price_version"),terms(r,"target"),ChangeTiming.valueOf(r.getString("timing")),r.getLong("due_now_minor"),r.getLong("next_amount_minor"),r.getString("currency"),instant(r,"effective_at"),instant(r,"expires_at"),r.getBoolean("tax_final"),r.getLong("subscription_revision"),r.getLong("policy_version"),r.getString("provider_quote_reference"));}
}
