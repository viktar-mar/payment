package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import java.time.Instant;
import java.util.*;
public interface SubscriptionObjectPort {
    enum Kind { SUBSCRIPTION, INVOICE, SETUP, PAYMENT, SCHEDULE }
    Optional<UUID> resolve(PaymentProviderType provider,PaymentAccountId account,Kind kind,String reference);
    void bind(PaymentProviderType provider,Subscription subscription,Kind kind,String reference,UUID bill,Instant now);
    void bindPayment(PaymentProviderType provider,Subscription subscription,String reference,UUID bill,dev.genericpayments.platform.payments.domain.PaymentId payment,Instant now);
}
