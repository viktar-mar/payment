package dev.genericpayments.platform.payments.adapter.out.persistence;
import dev.genericpayments.platform.payments.application.port.out.ClientPaymentAttemptPort;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentId;

@Repository
@Transactional
public class ClientPaymentAttemptPersistenceAdapter implements ClientPaymentAttemptPort {
    private final JdbcTemplate jdbc;
    public ClientPaymentAttemptPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean record(Baseline baseline) {
        Objects.requireNonNull(baseline);
        var existing=findByAttempt(baseline.attemptId());
        if(existing.isPresent())return identical(existing.get(),baseline);
        int inserted=jdbc.update("""
            insert into payment_client_attempts(attempt_id,payment_account_id,payment_id,customer_id,
                credentials_ref,external_account_id,initial_failed_charge_reference)
            values (?,?,?,?,?,?,?) on conflict(attempt_id) do nothing
            """,baseline.attemptId(),baseline.accountId().value(),baseline.paymentId().value(),baseline.customerId(),
            baseline.credentialsRef(),baseline.externalAccountId(),baseline.initialFailedChargeReference());
        return inserted==1||identical(findByAttempt(baseline.attemptId()).orElseThrow(),baseline);
    }
    private static boolean identical(Baseline existing,Baseline requested) {
        if(!existing.equals(requested))throw new IllegalStateException("Client attempt conflicts with stored baseline");
        return false;
    }
    @Transactional(readOnly=true)
    public Optional<Baseline> findByAttempt(UUID attemptId) {
        return jdbc.query("select * from payment_client_attempts where attempt_id=?",(rs,n) -> new Baseline(
            rs.getObject("attempt_id",UUID.class),new PaymentAccountId(rs.getObject("payment_account_id",UUID.class)),
            new PaymentId(rs.getObject("payment_id",UUID.class)),rs.getObject("customer_id",UUID.class),
            rs.getString("credentials_ref"),rs.getString("external_account_id"),rs.getString("initial_failed_charge_reference")),attemptId).stream().findFirst();
    }
}
