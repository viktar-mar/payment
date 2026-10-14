package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import static dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionPersistenceValues.*;
import dev.genericpayments.platform.subscriptions.application.port.out.BillingRecordStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.dao.OptimisticLockingFailureException;

@Repository @Transactional
public class BillingRecordPersistenceAdapter implements BillingRecordStore {
    private final JdbcTemplate jdbc;
    public BillingRecordPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(readOnly=true)
    public Optional<BillingRecord> find(SiteId site,UUID subscription,PaymentAccountId account,String reference){
        return jdbc.query("select * from subscription_billing_records where site_id=? and subscription_id=? and payment_account_id=? and provider_invoice_reference=?",this::map,site.value(),subscription,account.value(),reference).stream().findFirst();
    }
    public BillingRecord upsert(BillingRecord b){
        var v=new LinkedHashMap<String,Object>();v.put("id",b.id());v.put("site_id",b.siteId().value());v.put("subscription_id",b.subscriptionId());v.put("product_id",b.billedPrice().productId().value());v.put("payment_account_id",b.accountId().value());
        v.put("provider_invoice_reference",b.providerInvoiceReference());v.put("kind",b.kind().name());v.put("currency",b.currency());terms(v,"billed",b.billedPrice());terms(v,"previous",b.previousPrice());period(v,"period",b.period());
        v.put("amount_due_minor",b.amountDueMinor());v.put("settlement",b.settlement().name());v.put("version",b.version());v.put("created_at",time(b.createdAt()));v.put("updated_at",time(b.updatedAt()));
        int inserted=insert(jdbc,"subscription_billing_records",v,"on conflict(payment_account_id,provider_invoice_reference) do nothing");
        var stored=jdbc.query("select * from subscription_billing_records where payment_account_id=? and provider_invoice_reference=? for update",this::map,b.accountId().value(),b.providerInvoiceReference()).getFirst();
        if(!stored.siteId().equals(b.siteId())||!stored.subscriptionId().equals(b.subscriptionId())||stored.kind()!=b.kind()||!stored.billedPrice().equals(b.billedPrice())||!Objects.equals(stored.previousPrice(),b.previousPrice())||!stored.period().equals(b.period())||stored.amountDueMinor()!=b.amountDueMinor()||!stored.currency().equals(b.currency()))
            throw new IllegalArgumentException("Invoice identity or terms changed");
        if(inserted==1||stored.settlement()==b.settlement()||stored.settlement()==BillingRecord.Settlement.CONFIRMED_CASH||stored.settlement()==BillingRecord.Settlement.ZERO_AMOUNT)return stored;
        if(stored.version()!=b.version()||b.updatedAt().isBefore(stored.updatedAt()))throw new OptimisticLockingFailureException("Billing record changed");
        jdbc.update("update subscription_billing_records set settlement=?,updated_at=?,version=version+1 where id=?",b.settlement().name(),time(b.updatedAt()),stored.id());
        return lock(b.siteId(),stored.id());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public BillingRecord lock(SiteId site,UUID id){return jdbc.query("select * from subscription_billing_records where site_id=? and id=? for update",this::map,site.value(),id).stream().findFirst().orElseThrow();}
    BillingRecord map(ResultSet r,int row)throws SQLException{return new BillingRecord(uuid(r,"id"),new SiteId(uuid(r,"site_id")),uuid(r,"subscription_id"),new PaymentAccountId(uuid(r,"payment_account_id")),r.getString("provider_invoice_reference"),BillingRecord.Kind.valueOf(r.getString("kind")),terms(r,"billed"),terms(r,"previous"),period(r,"period"),r.getLong("amount_due_minor"),r.getString("currency"),BillingRecord.Settlement.valueOf(r.getString("settlement")),r.getLong("version"),instant(r,"created_at"),instant(r,"updated_at"));}
}
