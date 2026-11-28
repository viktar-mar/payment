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
    private final SubscriptionOperationStore operations;
    private final SubscriptionLifecycleLockPort lifecycle;
    private final LockPaymentAccountPort accounts;
    private final SubscriptionStore subscriptions;
    private final Clock clock;
    public OwnedFirstRenewalAdmissionService(OwnedFirstRenewalStore store,SubscriptionTransactionPort transaction,
            SubscriptionOperationStore operations,SubscriptionLifecycleLockPort lifecycle,LockPaymentAccountPort accounts,
            SubscriptionStore subscriptions,Clock clock) {
        this.store=store;this.transaction=transaction;this.clock=clock;
        this.operations=operations;this.lifecycle=lifecycle;this.accounts=accounts;this.subscriptions=subscriptions;
    }
    public Admission admit(OwnedClockSubscriptionReceipt receipt,String sourceSha) {
        java.util.Objects.requireNonNull(receipt);
        return transaction.required(()->{
            var i=receipt.intent();var owner=i.clock().owner();
            var candidate=subscriptions.findOwned(owner.account(),i.subscription()).orElseThrow();
            if(!candidate.siteId().equals(owner.site())||!candidate.customerId().equals(owner.customer()))throw new IllegalArgumentException("Owned renewal scope mismatch");
            var ids=new java.util.TreeSet<java.util.UUID>();
            ids.add(i.createOperation());
            operations.unresolved(candidate.siteId(),candidate.id()).forEach(o->ids.add(o.id()));
            for(var id:ids)operations.lock(id);
            lifecycle.lockActive(owner.site(),owner.customer());
            accounts.findByIdForUpdate(owner.account()).orElseThrow();
            subscriptions.lock(owner.site(),i.subscription());
            return store.admitPaid(receipt,sourceSha,clock.instant());
        });
    }
}
