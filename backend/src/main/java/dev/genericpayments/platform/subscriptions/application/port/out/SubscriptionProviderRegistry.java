package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
public interface SubscriptionProviderRegistry {
    SubscriptionProviderPort forAccount(PaymentAccount account);
}
