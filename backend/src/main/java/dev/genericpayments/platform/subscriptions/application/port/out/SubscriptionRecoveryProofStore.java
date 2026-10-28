package dev.genericpayments.platform.subscriptions.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.*;

/** Exact existing Billing invoice ownership. Association never fabricates an INVOICE_PAY ACK. */
public interface SubscriptionRecoveryProofStore {
    record Origin(UUID operation,UUID step) {}
    record Receipt(UUID operation,UUID subscription,PaymentAccountId account,UUID billingRecord,String reference,String invoice,
                   UUID originOperation,UUID originStep,UUID payStep,String allocation,String intent) {}
    Optional<Origin> origin(PaymentAccountId account,UUID subscription);
    Optional<BillingRecord> bill(PaymentAccountId account,UUID subscription,UUID billingRecord);
    Optional<Receipt> receipt(PaymentAccountId account,UUID operation);
    boolean available(PaymentAccountId account,UUID subscription,String invoice,UUID owner);
    void authorize(Subscription subscription,SubscriptionOperation operation,BillingRecord bill,Origin origin);
    Receipt associate(Subscription subscription,SubscriptionOperation operation,BillingRecord bill,Origin origin,String allocation,String intent);
    Receipt recordPay(Subscription subscription,SubscriptionOperation operation,BillingRecord bill,Origin origin,ProviderOperation step,
                      String owner,String allocation,String intent);
}
