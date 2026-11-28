package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockSubscriptionStore;
import dev.genericpayments.platform.subscriptions.domain.Subscription;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership.mismatch;

/** An explicitly composed schedule simulation, rooted in the original persisted clocked CREATE. */
record StripeBillingScheduleClock(OwnedClockSubscriptionReceipt receipt, StripeBillingClockSubscriptionStore store) {
    String requireScope(PaymentAccount account, Subscription local, CustomerBinding binding) {
        var intent=receipt.intent();
        var owner=intent.clock().owner();
        if (!store.requireReceipt(account.id(),local.id()).equals(receipt) ||
            !account.id().equals(owner.account()) || !owner.externalAccount().equals(account.externalAccountId()) ||
            !owner.credentialsRef().equals(account.credentialsRef()) || !owner.site().equals(local.siteId()) ||
            !owner.customer().equals(local.customerId()) || !intent.subscription().equals(local.id()) ||
            !receipt.reference().equals(local.providerReference()) || !intent.customerBinding().equals(binding.id()) ||
            !intent.customerCreationOperation().equals(binding.creationOperationId()) ||
            !intent.customerReference().equals(binding.providerReference())) throw mismatch();
        return intent.clock().reference();
    }

    void requireRemote(com.stripe.model.Subscription remote,String expected) {
        if (remote==null || !receipt.reference().equals(remote.getId()) || !expected.equals(remote.getTestClock()) ||
            !receipt.intent().customerReference().equals(remote.getCustomer()) ||
            !Long.valueOf(receipt.providerCreated()).equals(remote.getCreated()) || remote.getMetadata()==null ||
            !remote.getMetadata().entrySet().containsAll(StripeBillingOwnership.metadata(receipt.intent()).entrySet()))
            throw mismatch();
    }
}
