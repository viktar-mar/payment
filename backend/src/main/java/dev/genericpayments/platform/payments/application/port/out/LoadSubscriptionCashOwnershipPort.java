package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentId;
import java.util.Optional;
import java.util.UUID;

/** Persisted SUBSCRIPTION identity only, deliberately no Payment status/provider truth. */
public interface LoadSubscriptionCashOwnershipPort {
    record Ownership(UUID subscriptionId,String invoiceReference,Money amount) {}
    Optional<Ownership> findCashOwnership(PaymentAccountId accountId,PaymentId paymentId,String reference);
}
