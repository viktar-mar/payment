package dev.genericpayments.platform.subscriptions.application.model;
import dev.genericpayments.platform.subscriptions.domain.BillingRecord;
import dev.genericpayments.platform.payments.domain.PaymentId;
import java.util.List;
public record SubscriptionBillingView(BillingRecord bill,List<PaymentId> paymentIds) {public SubscriptionBillingView{paymentIds=List.copyOf(paymentIds);}}
