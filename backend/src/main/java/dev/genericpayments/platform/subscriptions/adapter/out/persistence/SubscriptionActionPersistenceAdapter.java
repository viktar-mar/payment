package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant;
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
    public ClaimDisposition claim(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String purpose,String digest,String authority,String key,String fingerprint,Instant now){
        if(replay(site,authority,key,fingerprint))return ClaimDisposition.REPLAY;
        if(!consume(site,customer,account,subscription,operation,purpose,digest,now))throw conflict();
        jdbc.update("update subscription_action_capabilities set confirmation_authority_scope=?,confirmation_key_digest=?,confirmation_fingerprint=? where token_digest=?",authority,key,fingerprint,digest);
        return ClaimDisposition.NEW;
    }
    public Optional<SubscriptionClientGrant> current(PaymentAccountId account,UUID operation){
        return jdbc.query("select * from subscription_action_capabilities where payment_account_id=? and operation_id=? and billing_key_version is not null and superseded_at is null for update",(r,n)->billing(r),account.value(),operation).stream().findFirst();
    }
    public Optional<SubscriptionClientGrant> billing(String digest){return jdbc.query("select * from subscription_action_capabilities where token_digest=? and billing_key_version is not null for update",(r,n)->billing(r),digest).stream().findFirst();}
    public void reserveBilling(SubscriptionClientGrant g){
        var current=current(g.account(),g.operation()).orElse(null);
        if(current!=null){if(current.consumedAt()!=null||current.expiresAt().isAfter(g.issuedAt())||!current.binding().equals(g.binding()))throw conflict();
            if(jdbc.update("update subscription_action_capabilities set superseded_at=? where id=? and consumed_at is null and superseded_at is null",Timestamp.from(g.issuedAt()),current.id())!=1)throw conflict();}
        var b=g.binding();
        int inserted=jdbc.update("""
            insert into subscription_action_capabilities(id,site_id,customer_id,payment_account_id,subscription_id,operation_id,purpose,token_digest,issued_at,expires_at,
              consent_purpose,recurring_consent,consent_policy_version,consent_at,billing_key_version,billing_step_id,billing_step_kind,billing_operation_key,billing_proof,billing_subscription_reference,billing_customer_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference,billing_schema,billing_setup_reference,billing_step_reference,billing_change_operation)
            select ?,?,?,?, ?,o.id,'PROVIDER_CLIENT',?,?,?,o.kind,true,o.policy_version,o.created_at,?,?,?,?,?,?,?,?,?,?,?,?,?,case when ?='change-v1' then o.id end
              from subscription_operations o where o.id=? and o.site_id=? and o.customer_id=? and o.subscription_id=? and o.provider_operation_key=?
              and ((?='payment-v1' and o.kind='CREATE' and o.fingerprint like 'v1.paid.%')
                or (?='setup-v1' and ((o.kind='CREATE' and o.fingerprint like 'v1.trial.%') or o.kind='SETUP'))
                or (?='resume-v1' and o.kind='RESUME' and o.fingerprint like 'v1.resume.none.%')
                or (?='change-v1' and o.kind='CHANGE' and o.fingerprint like 'v1.plan.%' and o.status in ('PROCESSING','REQUIRES_ACTION','UNKNOWN')
                  and exists(select 1 from stripe_plan_results r join stripe_plan_quotes p on p.id=r.quote_id
                    join subscriptions s on s.id=r.subscription_id and s.payment_account_id=r.payment_account_id
                    join site_subscription_policies policy on policy.site_id=s.site_id join payment_accounts a on a.id=s.payment_account_id
                    where r.operation_id=o.id and r.payment_account_id=? and r.step_id=? and r.invoice_reference=? and r.subscription_reference=?
                    and s.pending_operation_id=o.id and s.pending_price_id=p.target_price_id and s.pending_price_version=(p.descriptor->'target'->>'version')::integer
                    and s.billing_status='ACTIVE' and s.debt_billing_record_id is null and s.cancellation_effective_at is null
                    and policy.enabled and policy.version=o.policy_version and a.account_status='ACTIVE' and a.provider='STRIPE' and a.account_mode='SANDBOX')))
            """,g.id(),g.site().value(),g.customer().value(),g.account().value(),g.subscription(),g.tokenDigest(),Timestamp.from(g.issuedAt()),Timestamp.from(g.expiresAt()),g.keyVersion(),b.stepId(),b.stepKind(),b.operationKey(),b.proof(),b.subscriptionReference(),b.customerReference(),b.invoiceReference(),b.allocationReference(),b.intentReference(),b.schema(),b.setupReference(),b.stepReference(),b.schema(),g.operation(),g.site().value(),g.customer().value(),g.subscription(),b.operationKey(),b.schema(),b.schema(),b.schema(),b.schema(),g.account().value(),b.stepId(),b.invoiceReference(),b.subscriptionReference());
        if(inserted!=1)throw conflict();
    }
    public void bindSession(String digest,UUID session,String origin,Instant expires,Instant now){
        var g=billing(digest).orElseThrow(SubscriptionActionPersistenceAdapter::conflict);if(!g.available(now)||!expires.isAfter(now)||expires.isAfter(g.expiresAt()))throw conflict();
        if(g.sessionId()!=null){if(!g.sessionId().equals(session)||!g.origin().equals(origin)||!g.clientExpiresAt().equals(expires))throw conflict();return;}
        if(jdbc.update("update subscription_action_capabilities set client_session_id=?,client_origin=?,client_expires_at=? where token_digest=? and client_session_id is null and consumed_at is null and superseded_at is null",session,origin,Timestamp.from(expires),digest)!=1)throw conflict();
    }
    public ClaimDisposition claimBilling(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String digest,UUID session,String origin,String authority,String key,String fingerprint,Instant now){
        if(replay(site,authority,key,fingerprint))return ClaimDisposition.REPLAY;
        int updated=jdbc.update("""
            update subscription_action_capabilities set consumed_at=?,confirmation_authority_scope=?,confirmation_key_digest=?,confirmation_fingerprint=?
            where site_id=? and customer_id=? and payment_account_id=? and subscription_id=? and operation_id=? and purpose='PROVIDER_CLIENT' and token_digest=?
             and billing_key_version is not null and recurring_consent is true and consent_purpose in ('CREATE','SETUP','RESUME','CHANGE') and consumed_at is null and superseded_at is null and issued_at<=? and expires_at>?
             and client_session_id=? and client_origin=? and client_expires_at>?
            """,Timestamp.from(now),authority,key,fingerprint,site.value(),customer.value(),account.value(),subscription,operation,digest,Timestamp.from(now),Timestamp.from(now),session,origin,Timestamp.from(now));
        if(updated!=1)throw conflict();return ClaimDisposition.NEW;
    }
    private static SubscriptionClientGrant billing(java.sql.ResultSet r)throws java.sql.SQLException{
        return new SubscriptionClientGrant(r.getObject("id",UUID.class),new SiteId(r.getObject("site_id",UUID.class)),new CustomerId(r.getObject("customer_id",UUID.class)),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getObject("subscription_id",UUID.class),r.getObject("operation_id",UUID.class),r.getString("billing_key_version"),
            new SubscriptionClientGrant.Binding(r.getObject("billing_step_id",UUID.class),r.getString("billing_operation_key"),r.getString("billing_proof"),r.getString("billing_subscription_reference"),r.getString("billing_customer_reference"),r.getString("billing_invoice_reference"),r.getString("billing_allocation_reference"),r.getString("billing_intent_reference"),r.getString("billing_schema"),r.getString("billing_setup_reference")),r.getString("token_digest"),at(r,"issued_at"),at(r,"expires_at"),at(r,"consumed_at"),at(r,"superseded_at"),r.getObject("client_session_id",UUID.class),r.getString("client_origin"),at(r,"client_expires_at"));
    }
    private static Instant at(java.sql.ResultSet r,String column)throws java.sql.SQLException{var t=r.getTimestamp(column);return t==null?null:t.toInstant();}
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("SUBSCRIPTION_CONFLICT",409,"Subscription conflict");}
}
