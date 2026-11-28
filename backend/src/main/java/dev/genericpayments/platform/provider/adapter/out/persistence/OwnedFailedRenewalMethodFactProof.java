package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;

/** Explicit composition only; no transaction creation, legacy fallback or component registration. */
public final class OwnedFailedRenewalMethodFactProof implements SubscriptionIsolatedMethodProofPort {
    private final OwnedFailedRenewalMethodStore store;
    private final SubscriptionTransactionPort transaction;
    private final SubscriptionBillingTimePort billingTime;
    public OwnedFailedRenewalMethodFactProof(OwnedFailedRenewalMethodStore store,SubscriptionTransactionPort transaction,SubscriptionBillingTimePort billingTime){
        this.store=Objects.requireNonNull(store);this.transaction=Objects.requireNonNull(transaction);this.billingTime=Objects.requireNonNull(billingTime);
    }
    @Override public void requireAuthorized(Command command,Subscription subscription,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt){
        requireSample(transaction,billingTime,command,subscription,operations,billingAt,utcAt);
        store.requireMethodFactInTransaction(command,subscription,operations,billingAt,utcAt);
    }
    static void requireSample(SubscriptionTransactionPort transaction,SubscriptionBillingTimePort time,Command command,Subscription subscription,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt){
        if(!transaction.active()||command==null||subscription==null||operations==null||billingAt==null||utcAt==null
            ||command.account()==null||!subscription.paymentAccountId().equals(command.account().id())||!subscription.id().equals(command.subscriptionId()))throw unavailable();
        var sample=time.sample(subscription.paymentAccountId(),subscription.siteId(),subscription.customerId(),utcAt);
        if(sample==null||!sample.isolated()||!sample.billingAt().equals(billingAt)||!sample.equals(command.expectedBillingSample()))throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("ISOLATED_BILLING_UNSUPPORTED");}
}
