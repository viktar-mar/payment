package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository @Transactional(propagation=Propagation.MANDATORY)
public class SubscriptionActionPersistenceAdapter implements SubscriptionActionStore {
    private final JdbcTemplate jdbc;private final Clock clock;
    public SubscriptionActionPersistenceAdapter(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public void issue(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String purpose,String digest,Instant expiresAt){
        var now=clock.instant();if(!expiresAt.isAfter(now)||expiresAt.isAfter(now.plusSeconds(600)))throw conflict();
        jdbc.update("insert into subscription_action_capabilities(id,site_id,customer_id,payment_account_id,subscription_id,operation_id,purpose,token_digest,issued_at,expires_at) values (?,?,?,?,?,?,?,?,?,?) on conflict(token_digest) do nothing",UUID.randomUUID(),site.value(),customer.value(),account.value(),subscription,operation,purpose,digest,Timestamp.from(now),Timestamp.from(expiresAt));
        // An existing token never acquires a new binding, expiry or unconsumed state.
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscription_action_capabilities where site_id=? and customer_id=? and payment_account_id=? and subscription_id=? and operation_id=? and purpose=? and token_digest=?)",Boolean.class,site.value(),customer.value(),account.value(),subscription,operation,purpose,digest)))throw conflict();
    }
    public boolean consume(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String purpose,String digest,Instant now){
        return jdbc.update("update subscription_action_capabilities set consumed_at=? where site_id=? and customer_id=? and payment_account_id=? and subscription_id=? and operation_id=? and purpose=? and token_digest=? and consumed_at is null and issued_at<=? and expires_at>?",Timestamp.from(now),site.value(),customer.value(),account.value(),subscription,operation,purpose,digest,Timestamp.from(now),Timestamp.from(now))==1;
    }
    public void consent(String digest,String purpose,boolean accepted,long policy,Instant at){
        jdbc.update("update subscription_action_capabilities set consent_purpose=?,recurring_consent=?,consent_policy_version=?,consent_at=? where token_digest=? and consent_at is null",purpose,accepted,policy,Timestamp.from(at),digest);
    }
    public boolean replay(SiteId site,String authority,String key,String fingerprint){
        var rows=jdbc.queryForList("select confirmation_fingerprint from subscription_action_capabilities where site_id=? and confirmation_authority_scope=? and confirmation_key_digest=? for update",String.class,site.value(),authority,key);
        if(rows.isEmpty())return false;if(!rows.getFirst().equals(fingerprint))throw conflict();return true;
    }
    public void claim(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String purpose,String digest,String authority,String key,String fingerprint,Instant now){
        if(replay(site,authority,key,fingerprint))return;
        if(!consume(site,customer,account,subscription,operation,purpose,digest,now))throw conflict();
        jdbc.update("update subscription_action_capabilities set confirmation_authority_scope=?,confirmation_key_digest=?,confirmation_fingerprint=? where token_digest=?",authority,key,fingerprint,digest);
    }
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("SUBSCRIPTION_CONFLICT",409,"Subscription conflict");}
}
