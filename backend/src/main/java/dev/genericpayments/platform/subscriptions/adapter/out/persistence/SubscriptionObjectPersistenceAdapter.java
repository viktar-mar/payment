package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
public class SubscriptionObjectPersistenceAdapter implements SubscriptionObjectPort {
    private final JdbcTemplate jdbc;
    public SubscriptionObjectPersistenceAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void bindPayment(PaymentProviderType provider,Subscription s,String reference,UUID bill,dev.genericpayments.platform.payments.domain.PaymentId payment,Instant now){
        if(reference==null||!reference.matches("[A-Za-z0-9_.:/-]{1,255}"))throw new IllegalArgumentException("Invalid provider reference");
        jdbc.update("insert into subscription_provider_objects(id,site_id,subscription_id,payment_account_id,provider_type,object_type,provider_reference,billing_record_id,payment_id,created_at) values (?,?,?,?,?,'PAYMENT',?,?,?,?) on conflict do nothing",UUID.randomUUID(),s.siteId().value(),s.id(),s.paymentAccountId().value(),provider.name(),reference,bill,payment.value(),Timestamp.from(now));
        var rows=jdbc.queryForList("select site_id,subscription_id,billing_record_id,payment_id from subscription_provider_objects where provider_type=? and payment_account_id=? and object_type='PAYMENT' and provider_reference=? for update",provider.name(),s.paymentAccountId().value(),reference);
        if(rows.size()!=1)throw new IllegalArgumentException("Payment mapping unavailable");var row=rows.getFirst();
        if(!s.siteId().value().equals(row.get("site_id"))||!s.id().equals(row.get("subscription_id"))||!bill.equals(row.get("billing_record_id"))||!payment.value().equals(row.get("payment_id")))throw new IllegalArgumentException("Payment mapping ownership mismatch");
    }
    public Optional<UUID> resolve(PaymentProviderType provider,PaymentAccountId account,Kind kind,String reference){
        var found=jdbc.query("select subscription_id from subscription_provider_objects where provider_type=? and payment_account_id=? and object_type=? and provider_reference=?",(r,n)->r.getObject(1,UUID.class),provider.name(),account.value(),kind.name(),reference).stream().findFirst();
        if(found.isPresent()||kind!=Kind.SUBSCRIPTION)return found;
        // Task6 pins receipt identity before the first authoritative mapping exists.
        return jdbc.query("select s.id from subscriptions s join payment_accounts a on a.id=s.payment_account_id where a.provider=? and s.payment_account_id=? and s.provider_reference=?",(r,n)->r.getObject(1,UUID.class),provider.name(),account.value(),reference).stream().findFirst();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void bind(PaymentProviderType provider,Subscription s,Kind kind,String reference,UUID bill,Instant now){
        if(reference==null||!reference.matches("[A-Za-z0-9_.:/-]{1,255}"))throw new IllegalArgumentException("Invalid provider reference");
        jdbc.update("insert into subscription_provider_objects(id,site_id,subscription_id,payment_account_id,provider_type,object_type,provider_reference,billing_record_id,created_at) values (?,?,?,?,?,?,?,?,?) on conflict do nothing",UUID.randomUUID(),s.siteId().value(),s.id(),s.paymentAccountId().value(),provider.name(),kind.name(),reference,bill,Timestamp.from(now));
        var row=jdbc.queryForMap("select site_id,subscription_id,billing_record_id from subscription_provider_objects where provider_type=? and payment_account_id=? and object_type=? and provider_reference=? for update",provider.name(),s.paymentAccountId().value(),kind.name(),reference);
        if(!s.siteId().value().equals(row.get("site_id"))||!s.id().equals(row.get("subscription_id"))||!Objects.equals(bill,row.get("billing_record_id")))throw new IllegalArgumentException("Provider object ownership mismatch");
    }
}
