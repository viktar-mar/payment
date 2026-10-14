package dev.genericpayments.platform.subscriptions.adapter.out.integration;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountBillingGuardPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import org.springframework.stereotype.Component;
@Component
public class PaymentAccountSubscriptionGuardAdapter implements PaymentAccountBillingGuardPort {
    private final SubscriptionStore subscriptions;
    public PaymentAccountSubscriptionGuardAdapter(SubscriptionStore subscriptions){this.subscriptions=subscriptions;}
    public void requireMutable(PaymentAccountId id){if(subscriptions.hasNonTerminalAccountSubscriptions(id))throw new PaymentApplicationException("PAYMENT_ACCOUNT_BILLING_ACTIVE",409,"Payment account has active billing");}
}
