package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Short immutable evidence transactions; no provider IO and no provider-step row locking. */
@Repository @Transactional(propagation=Propagation.REQUIRES_NEW)
public class SubscriptionBillingProofPersistenceAdapter implements SubscriptionBillingProofStore {
    private final JdbcTemplate jdbc;
    public SubscriptionBillingProofPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Trial> trial(PaymentAccountId account,UUID subscription){return jdbc.query("select * from stripe_subscription_trial_proofs where payment_account_id=? and subscription_id=?",(r,n)->trial(r),account.value(),subscription).stream().findFirst();}
    public Trial reserveTrial(Subscription s,SubscriptionOperation o,long end){
        owner(s,o,true);
        if(o.kind()!=SubscriptionOperation.Kind.CREATE||!dev.genericpayments.platform.subscriptions.application.service.CreateSubscriptionFingerprint.startsTrial(o.fingerprint()))throw conflict();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscription_trial_eligibility where site_id=? and customer_id=? and product_id=? and reserved_by_operation=? and consumed_at is null)",Boolean.class,s.siteId().value(),s.customerId().value(),s.productId().value(),o.id())))throw conflict();
        jdbc.update("insert into stripe_subscription_trial_proofs(operation_id,site_id,customer_id,subscription_id,payment_account_id,requested_end) values (?,?,?,?,?,?) on conflict do nothing",o.id(),s.siteId().value(),s.customerId().value(),s.id(),s.paymentAccountId().value(),end);
        return trial(s.paymentAccountId(),s.id()).filter(t->t.operation().equals(o.id())).orElseThrow(SubscriptionBillingProofPersistenceAdapter::conflict);
    }
    public Trial bindTrial(Trial t,UUID step,String ref,String item,String invoice,long start){
        return bindTrialResult(t,step,ref,item,invoice,start);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Trial bindTrialInTransaction(Trial t,UUID step,String ref,String item,String invoice,long start){return bindTrialResult(t,step,ref,item,invoice,start);}
    private Trial bindTrialResult(Trial t,UUID step,String ref,String item,String invoice,long start){
        jdbc.update("update stripe_subscription_trial_proofs set step_id=?,subscription_reference=?,item_reference=?,invoice_reference=?,trial_start=? where operation_id=? and payment_account_id=? and trial_start is null",step,ref,item,invoice,start,t.operation(),t.account().value());
        return trial(t.account(),t.subscription()).filter(v->Objects.equals(v.step(),step)&&Objects.equals(v.subscriptionReference(),ref)&&Objects.equals(v.item(),item)&&Objects.equals(v.invoice(),invoice)&&Objects.equals(v.start(),start)&&v.requestedEnd()==t.requestedEnd()).orElseThrow(SubscriptionBillingProofPersistenceAdapter::conflict);
    }
    public Optional<Setup> setup(PaymentAccountId a,UUID operation){return jdbc.query("select * from stripe_subscription_setup_proofs where payment_account_id=? and operation_id=?",(r,n)->setup(r),a.value(),operation).stream().findFirst();}
    public Optional<Setup> setupReference(PaymentAccountId a,String ref){return jdbc.query("select * from stripe_subscription_setup_proofs where payment_account_id=? and setup_reference=?",(r,n)->setup(r),a.value(),ref).stream().findFirst();}
    public Optional<Setup> method(PaymentAccountId a,UUID subscription,String method){return jdbc.query("select * from stripe_subscription_setup_proofs where payment_account_id=? and subscription_id=? and method_reference=? order by operation_id limit 1",(r,n)->setup(r),a.value(),subscription,method).stream().findFirst();}
    public Setup recordSetup(Subscription s,SubscriptionOperation o,UUID step,String ref){
        owner(s,o,false);
        jdbc.update("insert into stripe_subscription_setup_proofs(operation_id,site_id,customer_id,subscription_id,payment_account_id,operation_kind,step_id,setup_reference) values (?,?,?,?,?,?,?,?) on conflict do nothing",o.id(),s.siteId().value(),s.customerId().value(),s.id(),s.paymentAccountId().value(),o.kind().name(),step,ref);
        return setup(s.paymentAccountId(),o.id()).filter(v->v.step().equals(step)&&v.reference().equals(ref)&&v.subscription().equals(s.id())).orElseThrow(SubscriptionBillingProofPersistenceAdapter::conflict);
    }
    public Setup bindMethod(Setup p,String method){
        jdbc.update("update stripe_subscription_setup_proofs set method_reference=? where operation_id=? and payment_account_id=? and step_id=? and setup_reference=? and method_reference is null",method,p.operation(),p.account().value(),p.step(),p.reference());
        return setup(p.account(),p.operation()).filter(v->v.step().equals(p.step())&&v.reference().equals(p.reference())&&Objects.equals(v.method(),method)).orElseThrow(SubscriptionBillingProofPersistenceAdapter::conflict);
    }
    public Optional<Resume> resume(PaymentAccountId a,UUID operation){return jdbc.query("select * from stripe_subscription_resume_proofs where payment_account_id=? and operation_id=?",(r,n)->new Resume(r.getObject("operation_id",UUID.class),r.getObject("subscription_id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getObject("step_id",UUID.class),r.getString("subscription_reference"),r.getString("invoice_reference"),new Subscription.Period(Instant.ofEpochSecond(r.getLong("period_start")),Instant.ofEpochSecond(r.getLong("period_end")))),a.value(),operation).stream().findFirst();}
    public void authorizeSetup(Subscription s,SubscriptionOperation o){
        owner(s,o,true);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscriptions s join subscription_operations o on o.subscription_id=s.id where s.id=? and o.id=? and s.provider_reference is not distinct from ? and o.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and ((o.kind='CREATE' and o.fingerprint like 'v1.trial.%' and s.provider_reference is null and s.billing_trial_policy='REQUIRED_BEFORE_TRIAL' and exists(select 1 from subscription_trial_eligibility e where e.site_id=s.site_id and e.customer_id=s.customer_id and e.product_id=s.product_id and e.reserved_by_operation=o.id and e.consumed_at is null)) or (o.kind='SETUP' and s.provider_reference is not null)))",Boolean.class,s.id(),o.id(),s.providerReference())))throw conflict();
    }
    public Resume recordResume(Subscription s,SubscriptionOperation o,UUID step,String invoice,Subscription.Period period){
        return recordResumeResult(s,o,step,invoice,period);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Resume recordResumeInTransaction(Subscription s,SubscriptionOperation o,UUID step,String invoice,Subscription.Period period){return recordResumeResult(s,o,step,invoice,period);}
    public void authorizeResume(Subscription s,SubscriptionOperation o){
        owner(s,o,true);
        if(o.kind()!=SubscriptionOperation.Kind.RESUME||!o.fingerprint().matches("v1\\.resume\\.none\\.[0-9a-f]{64}")||!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscriptions s join subscription_operations o on o.subscription_id=s.id where s.id=? and o.id=? and s.billing_status='PAUSED' and s.provider_reference=? and o.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))",Boolean.class,s.id(),o.id(),s.providerReference())))throw conflict();
    }
    private Resume recordResumeResult(Subscription s,SubscriptionOperation o,UUID step,String invoice,Subscription.Period period){
        owner(s,o,false);
        jdbc.update("insert into stripe_subscription_resume_proofs(operation_id,site_id,customer_id,subscription_id,payment_account_id,step_id,subscription_reference,invoice_reference,period_start,period_end) values (?,?,?,?,?,?,?,?,?,?) on conflict do nothing",o.id(),s.siteId().value(),s.customerId().value(),s.id(),s.paymentAccountId().value(),step,s.providerReference(),invoice,period.start().getEpochSecond(),period.end().getEpochSecond());
        return resume(s.paymentAccountId(),o.id()).filter(v->v.step().equals(step)&&v.invoice().equals(invoice)&&v.period().equals(period)&&v.reference().equals(s.providerReference())).orElseThrow(SubscriptionBillingProofPersistenceAdapter::conflict);
    }
    private void owner(Subscription s,SubscriptionOperation o,boolean dispatch){
        if(jdbc.queryForList("select id from subscription_operations where id=? and site_id=? and customer_id=? and subscription_id=? and kind=? and fingerprint=? and provider_operation_key=? for update",UUID.class,o.id(),s.siteId().value(),s.customerId().value(),s.id(),o.kind().name(),o.fingerprint(),o.providerOperationKey()).size()!=1)throw conflict();
        if(jdbc.queryForList("select id from sites where id=? for update",UUID.class,s.siteId().value()).size()!=1
            ||jdbc.queryForList("select id from customers where site_id=? and id=? for update",UUID.class,s.siteId().value(),s.customerId().value()).size()!=1
            ||jdbc.queryForList("select id from payment_accounts where id=? for update",UUID.class,s.paymentAccountId().value()).size()!=1
            ||jdbc.queryForList("select id from subscriptions where site_id=? and id=? and payment_account_id=? for update",UUID.class,s.siteId().value(),s.id(),s.paymentAccountId().value()).size()!=1)throw conflict();
        if(dispatch&&!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sites site join customers c on c.site_id=site.id join subscriptions s on s.site_id=site.id and s.customer_id=c.id join payment_accounts a on a.id=s.payment_account_id join site_subscription_policies p on p.site_id=site.id where s.id=? and site.status='ACTIVE' and c.status='ACTIVE' and a.account_status='ACTIVE' and s.billing_status<>'CANCELLED' and p.enabled and p.version=?)",Boolean.class,s.id(),o.policyVersion())))throw conflict();
    }
    private static Trial trial(ResultSet r)throws SQLException{return new Trial(r.getObject("operation_id",UUID.class),r.getObject("subscription_id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getLong("requested_end"),r.getObject("step_id",UUID.class),r.getString("subscription_reference"),r.getString("item_reference"),r.getString("invoice_reference"),r.getObject("trial_start",Long.class));}
    private static Setup setup(ResultSet r)throws SQLException{return new Setup(r.getObject("operation_id",UUID.class),r.getObject("subscription_id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getObject("step_id",UUID.class),r.getString("setup_reference"),r.getString("method_reference"));}
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider evidence does not match");}
}
