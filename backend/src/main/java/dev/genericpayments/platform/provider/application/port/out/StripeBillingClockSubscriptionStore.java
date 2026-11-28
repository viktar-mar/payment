package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Internal typed proof only, without provider traffic. Confirmation guards participate in the caller transaction. */
public interface StripeBillingClockSubscriptionStore {
    /** Caller transaction only; ordinary exclusion includes stopped admitted owners. */
    void requireOrdinaryConfirmation(PaymentAccountId account, dev.genericpayments.platform.sites.domain.SiteId site, dev.genericpayments.platform.customers.domain.CustomerId customer);
    /** Reloads original immutable proof and current authority in the caller transaction. */
    OwnedClockSubscriptionReceipt requireConfirmationReceipt(OwnedClockSubscriptionReceipt expected, PaymentAccount account,
        dev.genericpayments.platform.subscriptions.domain.Subscription subscription,
        dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation operation);
    OwnedClockSubscription admitFresh(PaymentAccount account, ClockOwner owner, UUID subscription, UUID operation, Instant admittedAt);
    Optional<OwnedClockSubscription> find(PaymentAccountId account, UUID subscription);
    OwnedClockSubscription requireCurrent(OwnedClockSubscription intent);
    Optional<OwnedClockSubscriptionReceipt> receipt(OwnedClockSubscription intent);
    OwnedClockSubscriptionReceipt requireReceipt(PaymentAccountId account, UUID subscription);
}
