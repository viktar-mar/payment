package dev.genericpayments.platform.subscriptions.adapter.out.persistence;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Repository;
/** Keyset pages; signatures are collection/owner/filter/limit bound and domain separated. */
@Repository
public class SubscriptionReadPersistenceAdapter implements SubscriptionReadPort {
    private final JdbcTemplate jdbc; private final SubscriptionPersistenceAdapter subscriptions;
    private final BillingRecordPersistenceAdapter bills; private final SubscriptionEventStore events;
    private final SubscriptionOperationPersistenceAdapter operations; private final Environment environment;
    public SubscriptionReadPersistenceAdapter(JdbcTemplate jdbc,SubscriptionPersistenceAdapter subscriptions,BillingRecordPersistenceAdapter bills,SubscriptionEventStore events,SubscriptionOperationPersistenceAdapter operations,Environment environment){this.jdbc=jdbc;this.subscriptions=subscriptions;this.bills=bills;this.events=events;this.operations=operations;this.environment=environment;}
    public RowPage list(SiteId site,CustomerId customer,ProductId product,String state,int limit,String cursor,java.time.Instant evaluatedAt){
        bounds(limit);Objects.requireNonNull(evaluatedAt);if(state!=null)try{Subscription.DisplayState.valueOf(state);}catch(IllegalArgumentException e){throw invalid();}
        String scope="list|"+site+"|"+customer+"|"+product+"|"+state+"|"+limit;
        String after=decode(scope,cursor);var args=new ArrayList<Object>();args.add(site.value());
        String sql="select * from subscriptions where site_id=?";
        if(customer!=null){sql+=" and customer_id=?";args.add(customer.value());}
        if(product!=null){sql+=" and product_id=?";args.add(product.value());}
        // Match Subscription.displayState at the same request instant used for response projection.
        // Apply before keyset/LIMIT: filtering a limited page would lose matches and break cursors.
        if(state!=null){sql+=" and (case when cancellation_effective_at is not null and ((billing_status='ACTIVE' and paid_through>?) or (billing_status='TRIALING' and trial_end>?)) then 'CANCEL_AT_PERIOD_END' else billing_status end)=?";
            args.add(java.sql.Timestamp.from(evaluatedAt));args.add(java.sql.Timestamp.from(evaluatedAt));args.add(state);}
        if(after!=null){sql+=" and id>?";args.add(uuid(after));}
        args.add(limit+1);
        var rows=jdbc.query(sql+" order by id limit ?",subscriptions::map,args.toArray());
        boolean more=rows.size()>limit;var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new RowPage(page,more?encode(scope,page.getLast().id().toString()):null);
    }
    public Optional<Subscription> current(SiteId site,CustomerId customer,ProductId product){
        return jdbc.query("select * from subscriptions where site_id=? and customer_id=? and product_id=? and billing_status<>'CANCELLED'",subscriptions::map,site.value(),customer.value(),product.value()).stream().findFirst();
    }
    public SubscriptionHistory events(SiteId site,UUID id,int limit,String cursor){
        bounds(limit);String scope="events|"+site+"|"+id+"|"+limit;String after=decode(scope,cursor);long seq=0;
        try{if(after!=null)seq=Long.parseLong(after);if(seq<0)throw invalid();}catch(NumberFormatException e){throw invalid();}
        var rows=events.list(site,id,limit+1,seq);boolean more=rows.size()>limit;
        var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new SubscriptionHistory(page,more?encode(scope,Long.toString(page.getLast().sequence())):null);
    }
    public QuerySubscriptionsUseCase.BillingPage billingRecords(SiteId site,UUID id,int limit,String cursor){
        bounds(limit);String scope="bills|"+site+"|"+id+"|"+limit;String after=decode(scope,cursor);
        var rows=after==null?jdbc.query("select * from subscription_billing_records where site_id=? and subscription_id=? order by id limit ?",bills::map,site.value(),id,limit+1):
            jdbc.query("select * from subscription_billing_records where site_id=? and subscription_id=? and id>? order by id limit ?",bills::map,site.value(),id,uuid(after),limit+1);
        boolean more=rows.size()>limit;
        var page=rows.subList(0,Math.min(limit,rows.size())).stream().map(b->new SubscriptionBillingView(b,linked(site,id,b.id()))).toList();
        return new QuerySubscriptionsUseCase.BillingPage(page,more?encode(scope,page.getLast().bill().id().toString()):null);
    }
    private List<PaymentId> linked(SiteId site,UUID id,UUID bill){
        var ids=jdbc.query("select distinct payment_id from subscription_provider_objects where site_id=? and subscription_id=? and billing_record_id=? and payment_id is not null order by payment_id limit 101",(r,n)->new PaymentId(r.getObject(1,UUID.class)),site.value(),id,bill);
        if(ids.size()>100)throw new PaymentApplicationException("RESPONSE_TOO_LARGE",413,"Subscription linked payments exceed limit");return ids;
    }
    public Optional<SubscriptionOperation> unresolvedOperation(SiteId site,UUID id){return operations.unresolved(site,id).stream().filter(o->o.kind()!=SubscriptionOperation.Kind.PORTAL&&o.kind()!=SubscriptionOperation.Kind.RECONCILE).findFirst();}
    public Optional<SubscriptionOperation> operation(SiteId site,UUID id){return jdbc.query("select * from subscription_operations where site_id=? and id=?",operations::map,site.value(),id).stream().findFirst();}
    public Optional<BillingRecord> bill(SiteId site,UUID id,UUID bill){return jdbc.query("select * from subscription_billing_records where site_id=? and subscription_id=? and id=?",bills::map,site.value(),id,bill).stream().findFirst();}
    private String encode(String scope,String value){
        String version=environment.getRequiredProperty("payment.customer-history.cursor.active-key-version");
        String data=Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return version+"."+data+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(version,scope+"|"+data));
    }
    private String decode(String scope,String cursor){
        if(cursor==null)return null;if(cursor.length()>4096)throw invalid();
        try{String[] p=cursor.split("\\.",-1);if(p.length!=3||!p[0].matches("v[1-9][0-9]{0,8}")||!p[1].matches("[A-Za-z0-9_-]{1,128}")||!p[2].matches("[A-Za-z0-9_-]{43}"))throw invalid();
            if(!MessageDigest.isEqual(mac(p[0],scope+"|"+p[1]),Base64.getUrlDecoder().decode(p[2])))throw invalid();
            return new String(Base64.getUrlDecoder().decode(p[1]),StandardCharsets.UTF_8);
        }catch(RuntimeException e){throw invalid();}
    }
    private byte[] mac(String version,String value){
        try{String key=environment.getProperty("payment.customer-history.cursor.known-keys."+version);if(key==null)throw invalid();
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(Base64.getDecoder().decode(key),"HmacSHA256"));
            return mac.doFinal(("subscriptions:v1|"+value).getBytes(StandardCharsets.UTF_8));
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException("Cursor signing unavailable");}
    }
    private static UUID uuid(String value){try{if(value.length()!=36)throw invalid();return UUID.fromString(value);}catch(IllegalArgumentException e){throw invalid();}}
    private static void bounds(int limit){if(limit<1||limit>100)throw invalid();}
    private static PaymentApplicationException invalid(){return new PaymentApplicationException("INVALID_REQUEST",400,"Invalid subscription page");}
}
