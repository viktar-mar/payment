package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.Admission;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import java.time.Clock;

/** Explicit internal consumer; intentionally not registered in the ordinary provider registry. */
public final class OwnedFirstRenewalAdmissionService {
    private final OwnedFirstRenewalStore store;
    private final SubscriptionTransactionPort transaction;
    private final Clock clock;
    public OwnedFirstRenewalAdmissionService(OwnedFirstRenewalStore store,SubscriptionTransactionPort transaction,
            SubscriptionOperationStore operations,SubscriptionLifecycleLockPort lifecycle,LockPaymentAccountPort accounts,
            SubscriptionStore subscriptions,Clock clock) {
        this.store=store;this.transaction=transaction;this.clock=clock;
    }
    public Admission admit(OwnedClockSubscriptionReceipt receipt,String sourceSha) {
        return transaction.required(()->store.admitPaid(receipt,sourceSha,clock.instant()));
    }
}
