package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalStore;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Explicit composition only. Reauthenticates isolated time for the locked owner and delegates
 * durable read/operation/debt/association proof without starting or suspending a transaction. */
public final class OwnedFailedRenewalFactProof implements SubscriptionIsolatedRecoveryProofPort {
    private final OwnedFailedRenewalStore store;
    private final SubscriptionTransactionPort transaction;
    private final SubscriptionBillingTimePort billingTime;
    public OwnedFailedRenewalFactProof(OwnedFailedRenewalStore store,SubscriptionTransactionPort transaction,SubscriptionBillingTimePort billingTime) {
        this.store=Objects.requireNonNull(store);this.transaction=Objects.requireNonNull(transaction);this.billingTime=Objects.requireNonNull(billingTime);
    }
    @Override public void requireAuthorized(Command command,Subscription subscription,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt) {
        if(!transaction.active()||command==null||subscription==null||operations==null||billingAt==null||utcAt==null
                ||command.account()==null||!subscription.paymentAccountId().equals(command.account().id())||!subscription.id().equals(command.subscriptionId()))throw unavailable();
        var sample=billingTime.sample(subscription.paymentAccountId(),subscription.siteId(),subscription.customerId(),utcAt);
        if(sample==null||!sample.isolated()||!sample.billingAt().equals(billingAt))throw unavailable();
        store.requireRecoveryFactInTransaction(command,subscription,operations,billingAt,utcAt);
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("ISOLATED_BILLING_UNSUPPORTED");}
}
