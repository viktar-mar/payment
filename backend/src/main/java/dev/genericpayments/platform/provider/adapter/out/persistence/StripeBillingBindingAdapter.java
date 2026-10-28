package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Immutable mapping, committed independently after the fenced dispatch receipt. No HTTP in this transaction. */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class StripeBillingBindingAdapter implements StripeBillingBindingPort {
    private final JdbcTemplate jdbc;
    private final ProviderOperationStore operations;
    public StripeBillingBindingAdapter(JdbcTemplate jdbc, ProviderOperationStore operations) {
        this.jdbc = jdbc; this.operations = operations;
    }
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<CustomerBinding> findCustomer(PaymentAccountId accountId, SiteId siteId, CustomerId customerId) {
        requireScope(accountId, siteId, customerId);
        return jdbc.query("select * from stripe_billing_customers where payment_account_id=? and site_id=? and customer_id=?",
            this::binding, accountId.value(), siteId.value(), customerId.value()).stream().findFirst();
    }
    @Override
    public CustomerBinding bindCustomer(PaymentAccountId accountId, SiteId siteId, CustomerId customerId,
                                       String reference, UUID originalOperationId) {
        requireScope(accountId, siteId, customerId);
        if (originalOperationId == null || reference == null || !reference.matches("cus_[A-Za-z0-9]{1,251}")) throw conflict();
        var snapshot = operations.findById(originalOperationId).orElseThrow(StripeBillingBindingAdapter::conflict);
        // Locks and revalidates operation -> original Account -> Site -> Customer before mapping.
        var operation = operations.lockRecovery(snapshot);
        if (operation.kind() != ProviderOperation.Kind.CUSTOMER_CREATE
            || operation.status() != ProviderOperation.Status.ACKNOWLEDGED
            || !accountId.equals(operation.accountId()) || !customerId.value().equals(operation.resourceId())
            || !reference.equals(operation.providerReference()) || operation.firstDispatchedAt() == null
            || jdbc.queryForList("select id from customers where id=? and site_id=?", UUID.class,
                customerId.value(), siteId.value()).size() != 1) throw conflict();
        try {
            jdbc.update("""
                insert into stripe_billing_customers(id,payment_account_id,site_id,customer_id,provider_reference,creation_operation_id)
                values (?,?,?,?,?,?) on conflict do nothing
                """, UUID.randomUUID(), accountId.value(), siteId.value(), customerId.value(), reference, originalOperationId);
            var rows = jdbc.query("""
                select * from stripe_billing_customers where payment_account_id=?
                 and ((site_id=? and customer_id=?) or provider_reference=?) for update
                """, this::binding, accountId.value(), siteId.value(), customerId.value(), reference);
            if (rows.size() != 1) throw conflict();
            var binding = rows.getFirst();
            if (!binding.accountId().equals(accountId) || !binding.siteId().equals(siteId)
                || !binding.customerId().equals(customerId) || !binding.providerReference().equals(reference)
                || !binding.creationOperationId().equals(originalOperationId)) throw conflict();
            return binding;
        } catch (DataIntegrityViolationException invalidProof) { throw conflict(); }
    }
    private CustomerBinding binding(ResultSet row, int index) throws SQLException {
        return new CustomerBinding(row.getObject("id", UUID.class),
            new PaymentAccountId(row.getObject("payment_account_id", UUID.class)),
            new SiteId(row.getObject("site_id", UUID.class)), new CustomerId(row.getObject("customer_id", UUID.class)),
            row.getString("provider_reference"), row.getObject("creation_operation_id", UUID.class), row.getLong("version"));
    }
    private static void requireScope(PaymentAccountId a, SiteId s, CustomerId c) {
        if (a == null || s == null || c == null) throw conflict();
    }
    private static PaymentApplicationException conflict() {
        return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT", 409, "Provider operation conflicts with the request");
    }
}
