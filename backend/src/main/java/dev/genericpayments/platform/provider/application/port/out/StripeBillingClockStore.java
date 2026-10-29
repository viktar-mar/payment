package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Short independent transactions only. Admission and READY never call another receipt transaction. */
public interface StripeBillingClockStore {
    enum StopReason { CLOCK_CREATE_FAILED, CLOCK_READY_FAILED, CUSTOMER_CREATE_FAILED, BINDING_FAILED, AUTHORITY_REVOKED }
    enum ReadyState { READY }
    record ReadyObservation(ReadyState state, String reference, long frozenTime,
                            long providerCreated, long providerDeletesAfter, Instant observedAt) {}
    ClockOwner admitFresh(PaymentAccount account, SiteId site, CustomerId customer, UUID run,
                         String apiVersion, long initialFrozenTime, Instant admittedAt);
    Optional<ClockOwner> owner(PaymentAccountId account, SiteId site, CustomerId customer);
    /** Revalidate exact immutable owner, active Account/Site/Customer, and no persisted stop. */
    void requireActive(ClockOwner owner);
    /** Called only by the original live create claimant after its single exact GET; never a recovery entry. */
    ReadyClock recordReady(ClockOwner owner, ProviderOperation acknowledged, ReadyObservation observation);
    Optional<ReadyClock> ready(UUID intent);
    void stop(ClockOwner owner, StopReason reason, Instant stoppedAt);
}
