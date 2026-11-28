package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;

/** Explicit successor selection. Durable rejection never switches to the legacy recovery proof. */
public final class OwnedFailedRenewalMethodRecoveryFactProof implements SubscriptionIsolatedRecoveryProofPort {
    private final OwnedFailedRenewalMethodStore store;
    private final SubscriptionTransactionPort transaction;
    private final SubscriptionBillingTimePort billingTime;
    public OwnedFailedRenewalMethodRecoveryFactProof(OwnedFailedRenewalMethodStore store,SubscriptionTransactionPort transaction,SubscriptionBillingTimePort billingTime){
        this.store=Objects.requireNonNull(store);this.transaction=Objects.requireNonNull(transaction);this.billingTime=Objects.requireNonNull(billingTime);
    }
    @Override public void requireAuthorized(Command command,Subscription subscription,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt){
        OwnedFailedRenewalMethodFactProof.requireSample(transaction,billingTime,command,subscription,operations,billingAt,utcAt);
        store.requireRecoveryFactInTransaction(command,subscription,operations,billingAt,utcAt);
    }
}
