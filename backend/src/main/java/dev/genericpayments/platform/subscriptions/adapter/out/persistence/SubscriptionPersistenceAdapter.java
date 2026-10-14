package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import static dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionPersistenceValues.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.dao.OptimisticLockingFailureException;

@Repository @Transactional
public class SubscriptionPersistenceAdapter implements SubscriptionStore {
    private final JdbcTemplate jdbc;
    public boolean hasNonTerminalProduct(SiteId site,CustomerId customer,ProductId product){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscriptions where site_id=? and customer_id=? and product_id=? and billing_status<>'CANCELLED')",Boolean.class,site.value(),customer.value(),product.value()));}
    public SubscriptionPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Subscription> find(SiteId site,UUID id){return jdbc.query("select * from subscriptions where site_id=? and id=?",this::map,site.value(),id).stream().findFirst();}
    public Optional<Subscription> findOwned(PaymentAccountId account,UUID id){return jdbc.query("select * from subscriptions where payment_account_id=? and id=?",this::map,account.value(),id).stream().findFirst();}
    @Transactional(propagation=Propagation.MANDATORY)
    public Subscription lock(SiteId site,UUID id){return jdbc.query("select * from subscriptions where site_id=? and id=? for update",this::map,site.value(),id).stream().findFirst().orElseThrow();}
    public Subscription save(Subscription s,long expected) {
        if(expected < -1 || s.revision()!=Math.addExact(expected,1))throw new OptimisticLockingFailureException("Invalid subscription revision");
        var v=values(s);
        if(expected==-1) {
            v.put("next_reconcile_at",time(s.createdAt()));
            insert(jdbc,"subscriptions",v,"");
        }
        else if(update(jdbc,"subscriptions",v,"id=:id and site_id=:site_id and customer_id=:customer_id and product_id=:product_id and payment_account_id=:payment_account_id and created_at=:created_at and revision=:expected",Map.of("expected",expected))!=1)
            throw new OptimisticLockingFailureException("Subscription revision changed");
        return find(s.siteId(),s.id()).orElseThrow();
    }
    public boolean hasBlockingCustomerSubscriptions(SiteId site,CustomerId customer){return blocking("s.site_id=? and s.customer_id=?","o.site_id=? and o.customer_id=?",site.value(),customer.value());}
    public boolean hasBlockingSiteSubscriptions(SiteId site){return blocking("s.site_id=?","o.site_id=?",site.value());}
    private boolean blocking(String subscriptionWhere,String operationWhere,Object... args){
        var all=new ArrayList<>(Arrays.asList(args));all.addAll(Arrays.asList(args));
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subscriptions s where "+subscriptionWhere+" and s.billing_status<>'CANCELLED') or exists(select 1 from subscription_operations o where "+operationWhere+" and o.kind in ('CREATE','CANCEL') and o.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))",Boolean.class,all.toArray()));
    }
    public boolean hasNonTerminalAccountSubscriptions(PaymentAccountId account){return Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(select 1 from subscriptions s where s.payment_account_id=? and
        (s.billing_status<>'CANCELLED' or exists(select 1 from subscription_operations o where o.subscription_id=s.id and o.kind in ('CREATE','CANCEL') and o.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN'))))
        """,Boolean.class,account.value()));}
    private Map<String,Object> values(Subscription s){
        var v=new LinkedHashMap<String,Object>();v.put("id",s.id());v.put("site_id",s.siteId().value());v.put("customer_id",s.customerId().value());v.put("product_id",s.productId().value());v.put("payment_account_id",s.paymentAccountId().value());v.put("currency",s.billingPrice().regularAmount().currency());
        terms(v,"billing",s.billingPrice());terms(v,"entitled",s.entitledPrice());terms(v,"pending",s.pendingChange()==null?null:s.pendingChange().target());
        v.put("billing_status",s.billingStatus().name());v.put("provider_reference",s.providerReference());period(v,"current_period",s.currentPeriod());period(v,"trial",s.trial());v.put("paid_through",time(s.paidThrough()));
        v.put("cancellation_effective_at",s.cancellation()==null?null:time(s.cancellation().effectiveAt()));
        v.put("pending_operation_id",s.pendingChange()==null?null:s.pendingChange().operationId());v.put("pending_effective_at",s.pendingChange()==null?null:time(s.pendingChange().effectiveAt()));v.put("pending_state",s.pendingChange()==null?null:s.pendingChange().state().name());
        v.put("debt_billing_record_id",s.debt()==null?null:s.debt().billingRecordId());period(v,"debt_period",s.debt()==null?null:s.debt().unpaidPeriod());v.put("grace_deadline",s.debt()==null?null:time(s.debt().graceDeadline()));
        v.put("terminal_reason",s.terminalReason());v.put("revision",s.revision());v.put("created_at",time(s.createdAt()));v.put("updated_at",time(s.updatedAt()));v.put("last_reconciled_at",time(s.lastReconciledAt()));return v;
    }
    Subscription map(ResultSet r,int row)throws SQLException {
        UUID pending=uuid(r,"pending_operation_id"),debt=uuid(r,"debt_billing_record_id");
        var cancel=instant(r,"cancellation_effective_at");
        return new Subscription(uuid(r,"id"),new SiteId(uuid(r,"site_id")),new CustomerId(uuid(r,"customer_id")),new ProductId(uuid(r,"product_id")),new PaymentAccountId(uuid(r,"payment_account_id")),
            terms(r,"billing"),terms(r,"entitled"),Subscription.Status.valueOf(r.getString("billing_status")),r.getString("provider_reference"),period(r,"current_period"),instant(r,"paid_through"),period(r,"trial"),
            cancel==null?null:new Subscription.Cancellation(cancel),pending==null?null:new Subscription.Change(pending,terms(r,"pending"),instant(r,"pending_effective_at"),Subscription.ChangeState.valueOf(r.getString("pending_state"))),
            debt==null?null:new Subscription.Debt(debt,period(r,"debt_period"),instant(r,"grace_deadline")),r.getString("terminal_reason"),r.getLong("revision"),instant(r,"created_at"),instant(r,"updated_at"),instant(r,"last_reconciled_at"));
    }
}
