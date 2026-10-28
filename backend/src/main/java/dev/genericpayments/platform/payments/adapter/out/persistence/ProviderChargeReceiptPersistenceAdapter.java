package dev.genericpayments.platform.payments.adapter.out.persistence;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.payments.application.port.out.ProviderChargeReceiptPort;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class ProviderChargeReceiptPersistenceAdapter implements ProviderChargeReceiptPort {
    private final JdbcTemplate jdbc;
    public ProviderChargeReceiptPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean record(PaymentAccountId account,PaymentId payment,String chargeId,boolean paid,String safeOutcome) {
        if(account==null||payment==null||chargeId==null||!chargeId.matches("ch_[A-Za-z0-9_]{1,200}")
                ||!(paid?"SUCCEEDED":"FAILED").equals(safeOutcome))throw new IllegalArgumentException("Invalid safe Charge receipt");
        int inserted=jdbc.update("""
            insert into provider_charge_receipts(payment_account_id,payment_id,charge_reference,paid,safe_outcome)
            values (?,?,?,?,?) on conflict(payment_account_id,charge_reference) do nothing
            """,account.value(),payment.value(),chargeId,paid,safeOutcome);
        if(inserted==1)return true;
        boolean same=Boolean.TRUE.equals(jdbc.queryForObject("""
            select payment_id=? and paid=? and safe_outcome=? from provider_charge_receipts
            where payment_account_id=? and charge_reference=?
            """,Boolean.class,payment.value(),paid,safeOutcome,account.value(),chargeId));
        if(!same)throw new IllegalStateException("Charge receipt conflicts with stored evidence");
        return false;
    }
    @Transactional(readOnly=true)
    public boolean contains(PaymentAccountId account,String chargeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from provider_charge_receipts where payment_account_id=? and charge_reference=?)",Boolean.class,account.value(),chargeId));
    }
}
