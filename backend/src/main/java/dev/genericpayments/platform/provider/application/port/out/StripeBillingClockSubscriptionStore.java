package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Internal typed proof only; each call is a short independent transaction without provider traffic. */
public interface StripeBillingClockSubscriptionStore {
    OwnedClockSubscription admitFresh(PaymentAccount account, ClockOwner owner, UUID subscription, UUID operation, Instant admittedAt);
    Optional<OwnedClockSubscription> find(PaymentAccountId account, UUID subscription);
    OwnedClockSubscription requireCurrent(OwnedClockSubscription intent);
    Optional<OwnedClockSubscriptionReceipt> receipt(OwnedClockSubscription intent);
    OwnedClockSubscriptionReceipt requireReceipt(PaymentAccountId account, UUID subscription);
}
