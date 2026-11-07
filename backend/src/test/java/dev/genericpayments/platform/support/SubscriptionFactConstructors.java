package dev.genericpayments.platform.support;

import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactService;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionReadPreparation;
import java.lang.reflect.Constructor;
import java.time.Clock;

/** The ordinary composition uses the service's concrete billing time and denied recovery proof. */
public final class SubscriptionFactConstructors {
    private SubscriptionFactConstructors() {}

    public static Constructor<SubscriptionFactService> methodEnabled() {
        var parameters=new java.util.ArrayList<Class<?>>(java.util.List.of(ordinary().getParameterTypes()));
        parameters.addAll(java.util.List.of(SubscriptionBillingTimePort.class,SubscriptionIsolatedRecoveryProofPort.class,
            SubscriptionIsolatedMethodProofPort.class,SubscriptionBillingProofStore.class));
        try{return SubscriptionFactService.class.getConstructor(parameters.toArray(Class<?>[]::new));}
        catch(NoSuchMethodException exception){throw new AssertionError("Explicit method subscription fact constructor is missing",exception);}
    }

    public static Constructor<SubscriptionFactService> ordinary() {
        try {
            return SubscriptionFactService.class.getConstructor(
            SubscriptionStore.class, SubscriptionOperationStore.class, TrialEligibilityStore.class,
            LockSitePort.class, CustomerStore.class, LockPaymentAccountPort.class, BillingRecordStore.class,
            SubscriptionCashPort.class, SubscriptionEventStore.class, SubscriptionCatalogPort.class, Clock.class,
            SubscriptionCallbackPort.class, SubscriptionObjectPort.class, BillingMetadataPort.class,
            SubscriptionPolicyPort.class, SubscriptionObservationPort.class, DigestPort.class,
            SubscriptionQuoteStore.class, SubscriptionSweepPort.class, SubscriptionReadPreparation.class,
            SubscriptionAuthorityPort.class);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("Ordinary subscription fact constructor is missing", exception);
        }
    }
}
