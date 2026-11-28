package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import org.springframework.stereotype.Component;
@Component
public class SubscriptionResumeReceiptTransaction {
    private final SubscriptionTransactionPort tx;private final SubscriptionOperationStore operations;private final LockSitePort sites;private final CustomerStore customers;
    private final LockPaymentAccountPort accounts;private final SubscriptionStore subscriptions;private final ProviderOperationStore steps;private final SubscriptionBillingProofStore proofs;
    public SubscriptionResumeReceiptTransaction(SubscriptionTransactionPort tx,SubscriptionOperationStore operations,LockSitePort sites,CustomerStore customers,LockPaymentAccountPort accounts,SubscriptionStore subscriptions,ProviderOperationStore steps,SubscriptionBillingProofStore proofs){this.tx=tx;this.operations=operations;this.sites=sites;this.customers=customers;this.accounts=accounts;this.subscriptions=subscriptions;this.steps=steps;this.proofs=proofs;}
    public ProviderOperation record(Subscription s,SubscriptionOperation o,ProviderOperation step,String owner,String invoice,Subscription.Period period){
        return tx.requiresNew(()->{
            var original=operations.lock(o.id());if(original.kind()!=SubscriptionOperation.Kind.RESUME||!original.subscriptionId().equals(s.id())||!original.fingerprint().equals(o.fingerprint())||!original.providerOperationKey().equals(o.providerOperationKey()))throw SubscriptionRuleErrors.conflict();
            sites.findByIdForUpdate(o.siteId()).orElseThrow(SubscriptionRuleErrors::conflict);customers.findByIdForUpdate(o.siteId(),o.customerId()).orElseThrow(SubscriptionRuleErrors::conflict);
            // Provider claim/ACK also lock Step before Account. Never invert that order here.
            steps.lockResumeReceipt(step);
            accounts.findByIdForUpdate(s.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict);var current=subscriptions.lock(o.siteId(),s.id());
            if(!current.paymentAccountId().equals(step.accountId())||!current.customerId().equals(o.customerId())||!java.util.Objects.equals(current.providerReference(),s.providerReference())||!current.billingPrice().equals(s.billingPrice())||!step.resourceId().equals(o.id()))throw SubscriptionRuleErrors.conflict();
            var ack=steps.acknowledgeInTransaction(step.id(),owner,step.version(),current.providerReference());
            proofs.recordResumeInTransaction(current,original,step.id(),invoice,period);return ack;
        });
    }
}
