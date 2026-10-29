package dev.genericpayments.platform.subscriptions.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Exact original cancellation request/result receipts. An ACK alone is never lifecycle proof. */
public interface SubscriptionCancellationProofStore {
    enum Mode { PERIOD_END, IMMEDIATE, WITHDRAW }
    /** Current server-projected state, never a synthetic local CANCEL operation. */
    record ObservedSchedule(UUID event,long revision,Instant boundary) {}
    record Receipt(UUID operation, UUID subscription, PaymentAccountId account, UUID step,
                   String reference, Mode mode, Instant boundary, UUID priorOperation, Instant acknowledgedAt,ObservedSchedule observed) {
        public Receipt(UUID operation,UUID subscription,PaymentAccountId account,UUID step,String reference,Mode mode,Instant boundary,UUID priorOperation,Instant acknowledgedAt){this(operation,subscription,account,step,reference,mode,boundary,priorOperation,acknowledgedAt,null);}
    }

    Optional<Receipt> receipt(PaymentAccountId account, UUID operation);
    Optional<Receipt> activeSchedule(PaymentAccountId account, UUID subscription, Instant boundary);
    default Optional<ObservedSchedule> observedSchedule(PaymentAccountId account,UUID subscription,Instant boundary){return Optional.empty();}
    default void requireObserved(Subscription subscription,ObservedSchedule observed){throw new UnsupportedOperationException();}
    void authorize(Subscription subscription, SubscriptionOperation operation);
    Receipt record(Subscription subscription, SubscriptionOperation operation, ProviderOperation step,
                   String owner, Mode mode, Instant boundary, UUID priorOperation, Instant acknowledgedAt);
    default Receipt recordObserved(Subscription subscription,SubscriptionOperation operation,ProviderOperation step,
        String owner,ObservedSchedule observed,Instant acknowledgedAt){throw new UnsupportedOperationException();}
}
