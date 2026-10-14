package dev.genericpayments.platform.provider.adapter.out.fake;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.payments.application.port.out.LoadSubscriptionCashOwnershipPort;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Payment is consulted only for identity/ownership; simulator cash owns provider truth. */
@Repository
public class FakeSubscriptionCashLookupAdapter {
    private final JdbcTemplate jdbc;
    private final LoadSubscriptionCashOwnershipPort ownership;
    public FakeSubscriptionCashLookupAdapter(JdbcTemplate jdbc,LoadSubscriptionCashOwnershipPort ownership){this.jdbc=jdbc;this.ownership=ownership;}
    public Optional<ProviderState> lookup(ProviderPaymentLookup lookup) {
        if(lookup.account().provider()!=PaymentProviderType.FAKE)throw new IllegalArgumentException("Payment account provider mismatch");
        var owner=ownership.findCashOwnership(lookup.account().id(),lookup.paymentId(),lookup.reference());
        if(owner.isEmpty())return Optional.empty();
        var identity=owner.get();
        var rows=jdbc.queryForList("""
            select status from fake_subscription_cash_objects
            where payment_account_id=? and provider_reference=? and subscription_id=? and invoice_reference=? and amount_minor=? and currency=?
            """,lookup.account().id().value(),lookup.reference(),identity.subscriptionId(),identity.invoiceReference(),identity.amount().minorUnits(),identity.amount().currency());
        if(rows.size()!=1)throw new IllegalArgumentException("Provider object not found");
        return Optional.of(new ProviderState(lookup.reference(),ProviderState.Status.valueOf((String)rows.getFirst().get("status"))));
    }
    public void requireRefundable(ProviderRefundRequest request) {
        Payment p=request.payment();
        var identity=ownership.findCashOwnership(request.account().id(),p.id(),p.providerPaymentReference()).orElseThrow(()->new IllegalArgumentException("Provider object not found"));
        if(!identity.subscriptionId().equals(p.subscriptionContext().subscriptionId())||!identity.invoiceReference().equals(p.subscriptionContext().providerInvoiceReference())||!identity.amount().equals(p.money()))throw new IllegalArgumentException("Provider cash ownership mismatch");
        var state=lookup(ProviderPaymentLookup.forPayment(request.account(),p.id(),p.providerPaymentReference())).orElseThrow(()->new IllegalArgumentException("Provider object not found"));
        if(state.status()!=ProviderState.Status.SUCCEEDED)throw new IllegalArgumentException("Provider cash is not refundable");
    }
}
