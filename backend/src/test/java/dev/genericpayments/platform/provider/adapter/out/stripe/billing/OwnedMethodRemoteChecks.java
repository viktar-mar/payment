package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.model.SetupIntent;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.subscriptions.domain.*;

/** Test-only bridge to the unchanged ordinary setup response validator. */
public final class OwnedMethodRemoteChecks {
    private OwnedMethodRemoteChecks() {}
    public static void requireSetup(SetupIntent remote,Subscription subscription,SubscriptionOperation operation,
            CustomerBinding binding,ProviderOperation step) {
        StripeSubscriptionProviderAdapter.requireSetupRemote(remote,subscription,operation,binding,step);
    }
}
