package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationReadPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderOperationReadAdapter implements ProviderOperationReadPort {
    private final JdbcTemplate jdbc;
    public ProviderOperationReadAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String PROJECTION="""
        select o.id,o.payment_account_id,a.account_mode,o.kind,o.status,o.provider_reference,o.first_dispatched_at,
          o.retry_before,o.next_attempt_at,o.safe_error_code,o.resource_id,o.manual_review_required,
          p.id payment_id,coalesce(p.site_id,product.site_id,price.site_id) site_id
        from provider_operations o
        join payment_accounts a on a.id=o.payment_account_id and a.provider='STRIPE'
        left join refunds r on o.kind='REFUND_CREATE' and r.id=o.resource_id
        left join payments p on p.payment_account_id=o.payment_account_id
          and ((o.kind='PAYMENT_PREPARE' and p.id=o.resource_id) or (o.kind='REFUND_CREATE' and p.id=r.payment_id))
        left join products product on o.kind='PRODUCT_CREATE' and product.id=o.resource_id
        left join prices price on o.kind='PRICE_CREATE' and price.id=o.resource_id
        """;
    public boolean paymentExists(PaymentId paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from payments where id=?)",Boolean.class,paymentId.value()));
    }
    public List<ProviderOperationView> forPayment(PaymentId paymentId) {
        return jdbc.query(PROJECTION+" where p.id=? order by o.id limit 100",this::operation,paymentId.value());
    }
    public List<ProviderOperationView> unresolved(UUID after,int limit) {
        return jdbc.query(PROJECTION+"""
            where (o.status in ('UNKNOWN','DISPATCHING','FAILED') or o.manual_review_required
              or (o.kind in ('PRODUCT_CREATE','PRICE_CREATE') and o.status='ACKNOWLEDGED'
                  and o.safe_error_code='PUBLICATION_REQUIRED'))
              and o.id > ? order by o.id limit ?
            """,this::operation,after==null?new UUID(0,0):after,limit);
    }
    public List<DeadProviderReferenceView> deadReferences(UUID after,int limit) {
        return jdbc.query("""
            select i.id,i.payment_account_id,a.account_mode,i.provider_event_id,i.provider_object_id,i.reference_object_type,
              i.occurred_at,i.received_at,i.attempt_count,i.error_category,i.status from provider_inbox i
            join payment_accounts a on a.id=i.payment_account_id
            where i.event_family='PROVIDER_REFERENCE' and i.status='DEAD' and i.id > ? order by i.id limit ?
            """,(rs,n)->new DeadProviderReferenceView(rs.getObject("id",UUID.class),rs.getObject("payment_account_id",UUID.class),
                rs.getString("account_mode"),rs.getString("provider_event_id"),rs.getString("provider_object_id"),rs.getString("reference_object_type"),
                instant(rs,"occurred_at"),instant(rs,"received_at"),rs.getInt("attempt_count"),rs.getString("error_category"),rs.getString("status")),
            after==null?new UUID(0,0):after,limit);
    }
    private ProviderOperationView operation(ResultSet rs,int n)throws SQLException {
        return new ProviderOperationView(rs.getObject("id",UUID.class),rs.getObject("payment_id",UUID.class),
            rs.getObject("payment_account_id",UUID.class),rs.getString("account_mode"),rs.getString("kind"),rs.getString("status"),rs.getString("provider_reference"),
            instant(rs,"first_dispatched_at"),instant(rs,"retry_before"),instant(rs,"next_attempt_at"),rs.getString("safe_error_code"),
            rs.getObject("resource_id",UUID.class),rs.getObject("site_id",UUID.class),rs.getBoolean("manual_review_required"));
    }
    private static Instant instant(ResultSet rs,String column)throws SQLException {
        var value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }
}
