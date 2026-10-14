package dev.genericpayments.platform.payments.adapter.out.persistence;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Repository
public class PaymentTransitionPersistenceAdapter implements PaymentTransitionPort {
    private final JdbcTemplate jdbc;private final LoadPaymentPort payments;
    public PaymentTransitionPersistenceAdapter(JdbcTemplate jdbc,LoadPaymentPort payments){this.jdbc=jdbc;this.payments=payments;}
    @Override public boolean hasAmbientTransaction(){return TransactionSynchronizationManager.isActualTransactionActive();}
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW) public <T>T transaction(Supplier<T> work){return work.get();}
    @Override @Transactional(readOnly=true) public Optional<Identity> resolve(PaymentAccountId account,String reference) {
        var rows=jdbc.query("select p.id,p.site_id,s.site_key,a.provider from payments p join sites s on s.id=p.site_id join payment_accounts a on a.id=p.payment_account_id where p.payment_account_id=? and p.provider_payment_reference=?",(r,n)->new Identity(new SiteId(r.getObject("site_id",UUID.class)),new PaymentId(r.getObject("id",UUID.class)),new Stage2TelemetryPort.Context(r.getString("site_key"),Stage2TelemetryPort.Provider.valueOf(r.getString("provider")))),account.value(),reference);
        return rows.size()==1?Optional.of(rows.getFirst()):Optional.empty();
    }
    @Override @Transactional(readOnly=true) public void requireProvider(PaymentAccountId account,PaymentProviderType provider) {
        var rows=jdbc.queryForList("select provider from payment_accounts where id=?",String.class,account.value());if(rows.size()!=1||!rows.getFirst().equals(provider.name()))throw new IllegalStateException("IDENTITY_MISMATCH");
    }
    @Override @Transactional(readOnly=true) public Optional<RefundIdentity> resolveRefund(PaymentAccountId account,String reference){
        var rows=jdbc.query("select r.id as refund_id,p.id,p.site_id,s.site_key,a.provider from refunds r join payments p on p.id=r.payment_id join sites s on s.id=p.site_id join payment_accounts a on a.id=p.payment_account_id where p.payment_account_id=? and r.provider_refund_reference=?",(r,n)->new RefundIdentity(new Identity(new SiteId(r.getObject("site_id",UUID.class)),new PaymentId(r.getObject("id",UUID.class)),new Stage2TelemetryPort.Context(r.getString("site_key"),Stage2TelemetryPort.Provider.valueOf(r.getString("provider")))),new RefundId(r.getObject("refund_id",UUID.class))),account.value(),reference);
        return rows.size()==1?Optional.of(rows.getFirst()):Optional.empty();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY) public Payment lock(Identity identity){return payments.findByIdForUpdate(identity.siteId(),identity.paymentId()).orElseThrow(() -> new IllegalStateException("UNRESOLVED_OBJECT"));}
    @Override @Transactional(propagation=Propagation.MANDATORY) public void verifyLease(Payment p,String owner,Instant now) {
        var rows=jdbc.queryForList("select id from payments where id=? and site_id=? and reconciliation_lease_owner=? and reconciliation_lease_expires_at>?",p.id().value(),p.siteId().value(),owner,Timestamp.from(now));if(rows.size()!=1)throw new IllegalStateException("RECONCILIATION_LEASE_LOST");
    }
}
