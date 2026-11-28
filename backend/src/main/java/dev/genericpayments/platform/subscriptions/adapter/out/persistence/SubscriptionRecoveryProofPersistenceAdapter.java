package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionPersistenceValues.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionRecoveryProofStore;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionRecoveryTransaction;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Association is a distinct local RECOVER result; only an actual pay dispatch gets a pay ACK. */
@Repository @Transactional(propagation=Propagation.REQUIRES_NEW)
public class SubscriptionRecoveryProofPersistenceAdapter implements SubscriptionRecoveryProofStore {
    private final JdbcTemplate jdbc;private final ProviderOperationStore steps;
    public SubscriptionRecoveryProofPersistenceAdapter(JdbcTemplate jdbc,ProviderOperationStore steps){this.jdbc=jdbc;this.steps=steps;}
    public Optional<Origin> origin(PaymentAccountId account,UUID subscription){
        var rows=jdbc.query("""
            select o.id,step.id step_id from subscription_operations o join subscriptions s on s.id=o.subscription_id
            join provider_operations step on step.resource_id=o.id and step.payment_account_id=s.payment_account_id
              and step.kind='SUBSCRIPTION_CREATE' and step.operation_key=o.provider_operation_key
              and step.status='ACKNOWLEDGED' and step.provider_reference=s.provider_reference
            where s.id=? and s.payment_account_id=? and o.kind='CREATE' and o.status='SUCCEEDED' and o.provider_reference=s.provider_reference
            limit 2
            """,(r,n)->new Origin(r.getObject("id",UUID.class),r.getObject("step_id",UUID.class)),subscription,account.value());
        if(rows.size()>1)throw conflict();return rows.stream().findFirst();
    }
    public Optional<BillingRecord> bill(PaymentAccountId account,UUID subscription,UUID id){return jdbc.query("select * from subscription_billing_records where id=? and subscription_id=? and payment_account_id=?",this::bill,id,subscription,account.value()).stream().findFirst();}
    public Optional<Receipt> receipt(PaymentAccountId account,UUID operation){return jdbc.query("select * from stripe_subscription_recovery_proofs where payment_account_id=? and operation_id=?",this::receipt,account.value(),operation).stream().findFirst();}
    public boolean available(PaymentAccountId account,UUID subscription,String invoice,UUID owner){return availableForConfirmation(account,subscription,invoice,owner,null);}
    public boolean availableForConfirmation(PaymentAccountId account,UUID subscription,String invoice,UUID owner,UUID root){

        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select not exists(select 1 from subscription_action_capabilities g where g.payment_account_id=? and g.subscription_id=? and g.billing_invoice_reference=?
              and (g.operation_id<>? or g.consumed_at is not null and not exists(select 1 from subscription_confirmation_chains c where c.id=g.id and c.id=? and c.operation_id=g.operation_id)))
            and not exists(select 1 from stripe_subscription_recovery_proofs p where p.payment_account_id=? and p.subscription_id=? and p.invoice_reference=? and p.operation_id<>?)
            and not exists(select 1 from provider_operations step join subscription_operations o on o.id=step.resource_id
              join subscription_billing_records b on b.id::text=split_part(o.fingerprint,'.',3)
              where step.payment_account_id=? and o.subscription_id=? and b.provider_invoice_reference=? and step.kind='INVOICE_PAY'
                and step.resource_id<>? and (step.first_dispatched_at is not null or step.status in ('DISPATCHING','UNKNOWN','ACKNOWLEDGED')))
            """,Boolean.class,account.value(),subscription,invoice,owner,root,account.value(),subscription,invoice,owner,account.value(),subscription,invoice,owner));
    }
    public void authorize(Subscription s,SubscriptionOperation o,BillingRecord b,Origin origin){requireCurrent(s,o,b,origin,null);}
    public Receipt associate(Subscription s,SubscriptionOperation o,BillingRecord b,Origin origin,String allocation,String intent){
        requireCurrent(s,o,b,origin,null);
        if(allocation==null||intent==null||!jdbc.queryForList("select id from provider_operations where payment_account_id=? and resource_id=? and kind='INVOICE_PAY' and first_dispatched_at is not null",UUID.class,s.paymentAccountId().value(),o.id()).isEmpty())throw conflict();
        return insert(s,o,b,origin,null,allocation,intent);
    }
    public Receipt recordPay(Subscription s,SubscriptionOperation o,BillingRecord b,Origin origin,ProviderOperation step,String owner,String allocation,String intent){
        requireCurrent(s,o,b,origin,step);
        if(step.kind()!=ProviderOperation.Kind.INVOICE_PAY||!step.resourceId().equals(o.id())||!step.accountId().equals(s.paymentAccountId())||!step.operationKey().equals(o.providerOperationKey()+":INVOICE_PAY"))throw conflict();
        steps.acknowledgeInTransaction(step.id(),owner,step.version(),b.providerInvoiceReference());
        return insert(s,o,b,origin,step.id(),allocation,intent);
    }
    private Receipt insert(Subscription s,SubscriptionOperation o,BillingRecord b,Origin origin,UUID pay,String allocation,String intent){
        if((allocation==null)!=(intent==null))throw conflict();
        jdbc.update("""
            insert into stripe_subscription_recovery_proofs(operation_id,site_id,customer_id,subscription_id,payment_account_id,operation_kind,
                billing_record_id,subscription_reference,invoice_reference,origin_operation,origin_step,origin_kind,pay_step,pay_kind,allocation_reference,intent_reference)
            values (?,?,?,?,?,'RECOVER',?,?,?,?,?,'SUBSCRIPTION_CREATE',?,case when ?::uuid is null then null else 'INVOICE_PAY' end,?,?) on conflict(operation_id) do nothing
            """,o.id(),s.siteId().value(),s.customerId().value(),s.id(),s.paymentAccountId().value(),b.id(),s.providerReference(),b.providerInvoiceReference(),origin.operation(),origin.step(),pay,pay,allocation,intent);
        var expected=new Receipt(o.id(),s.id(),s.paymentAccountId(),b.id(),s.providerReference(),b.providerInvoiceReference(),origin.operation(),origin.step(),pay,allocation,intent);
        return receipt(s.paymentAccountId(),o.id()).filter(expected::equals).orElseThrow(SubscriptionRecoveryProofPersistenceAdapter::conflict);
    }
    private void requireCurrent(Subscription s,SubscriptionOperation o,BillingRecord b,Origin origin,ProviderOperation pay){
        if(o.kind()!=SubscriptionOperation.Kind.RECOVER||!SubscriptionRecoveryTransaction.recoveryBill(o).filter(b.id()::equals).isPresent()||!o.subscriptionId().equals(s.id())||!b.subscriptionId().equals(s.id())||!b.accountId().equals(s.paymentAccountId())
            ||b.settlement()!=BillingRecord.Settlement.OPEN||b.amountDueMinor()<=0||b.amountDueMinor()!=b.billedPrice().regularAmount().minorUnits()||!b.currency().equals(b.billedPrice().regularAmount().currency())||s.debt()==null||!s.debt().billingRecordId().equals(b.id())||!s.debt().unpaidPeriod().equals(b.period()))throw conflict();
        if(jdbc.queryForList("select id from subscription_operations where id=? and version=? and fingerprint=? and provider_operation_key=? and status in ('RESERVED','PROCESSING','UNKNOWN','REQUIRES_ACTION') for update",UUID.class,o.id(),o.version(),o.fingerprint(),o.providerOperationKey()).size()!=1)throw conflict();
        jdbc.queryForList("select id from sites where id=? for update",UUID.class,s.siteId().value());jdbc.queryForList("select id from customers where site_id=? and id=? for update",UUID.class,s.siteId().value(),s.customerId().value());
        if(pay!=null)steps.lockSubscriptionMutationReceipt(pay);
        // Both provider Steps precede Account; association never synthesizes a pay Step.
        if(jdbc.queryForList("select id from provider_operations where id=? and resource_id=? and payment_account_id=? and kind='SUBSCRIPTION_CREATE' and status='ACKNOWLEDGED' and provider_reference=? for share",UUID.class,origin.step(),origin.operation(),s.paymentAccountId().value(),s.providerReference()).size()!=1)throw conflict();
        jdbc.queryForList("select id from payment_accounts where id=? for update",UUID.class,s.paymentAccountId().value());jdbc.queryForList("select id from subscriptions where id=? for update",UUID.class,s.id());
        if(!bill(s.paymentAccountId(),s.id(),b.id()).filter(b::equals).isPresent()||!origin(s.paymentAccountId(),s.id()).filter(origin::equals).isPresent()||!available(s.paymentAccountId(),s.id(),b.providerInvoiceReference(),o.id()))throw conflict();
        if(jdbc.queryForList("""
            select s.id from subscriptions s join sites site on site.id=s.site_id join customers c on c.id=s.customer_id and c.site_id=s.site_id
              join payment_accounts a on a.id=s.payment_account_id join site_subscription_policies p on p.site_id=s.site_id
              join provider_operations root on root.id=?
            where s.id=? and s.site_id=? and s.customer_id=? and s.payment_account_id=? and s.provider_reference=? and s.revision=?
              and s.billing_status<>'CANCELLED' and s.pending_operation_id is null and s.debt_billing_record_id=?
              and s.billing_price_id=? and s.billing_price_version=? and site.status='ACTIVE' and c.status='ACTIVE' and p.enabled and p.version=?
              and a.provider='STRIPE' and a.account_mode='SANDBOX' and a.account_status='ACTIVE'
              and root.external_account_id=a.external_account_id and root.credentials_ref=a.credentials_ref
              and not exists(select 1 from subscription_operations other where other.subscription_id=s.id and other.id<>?
                and other.kind not in ('PORTAL','RECONCILE') and other.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))
            """,UUID.class,origin.step(),s.id(),s.siteId().value(),s.customerId().value(),s.paymentAccountId().value(),s.providerReference(),o.expectedRevision(),b.id(),b.billedPrice().priceId().value(),b.billedPrice().version(),o.policyVersion(),o.id()).size()!=1)throw conflict();
    }
    private BillingRecord bill(ResultSet r,int n)throws SQLException{return new BillingRecord(uuid(r,"id"),new SiteId(uuid(r,"site_id")),uuid(r,"subscription_id"),new PaymentAccountId(uuid(r,"payment_account_id")),r.getString("provider_invoice_reference"),BillingRecord.Kind.valueOf(r.getString("kind")),terms(r,"billed"),terms(r,"previous"),period(r,"period"),r.getLong("amount_due_minor"),r.getString("currency"),BillingRecord.Settlement.valueOf(r.getString("settlement")),r.getLong("version"),instant(r,"created_at"),instant(r,"updated_at"));}
    private Receipt receipt(ResultSet r,int n)throws SQLException{return new Receipt(uuid(r,"operation_id"),uuid(r,"subscription_id"),new PaymentAccountId(uuid(r,"payment_account_id")),uuid(r,"billing_record_id"),r.getString("subscription_reference"),r.getString("invoice_reference"),uuid(r,"origin_operation"),uuid(r,"origin_step"),uuid(r,"pay_step"),r.getString("allocation_reference"),r.getString("intent_reference"));}
    private static PaymentApplicationException conflict(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider evidence does not match");}
}
