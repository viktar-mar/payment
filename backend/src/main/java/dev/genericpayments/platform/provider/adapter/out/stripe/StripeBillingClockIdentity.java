package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.ClockOwner;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import java.util.UUID;

/** Versioned identity encodings; ordinary Customer fingerprints are unchanged. */
public final class StripeBillingClockIdentity {
    private StripeBillingClockIdentity() {}
    public static String name(UUID intent) { return "billing_clock_"+intent; }
    public static String createKey(PaymentAccountId account, UUID intent) { return "billing_clock_"+account.value()+"_"+intent; }
    public static String createFingerprint(DigestPort digest, PaymentAccount account, UUID intent, UUID run,
            UUID site, UUID customer, String api, long frozen) {
        return StripeRequestFingerprint.of(digest,account,"TEST_CLOCK_CREATE_V1",intent.toString(),run.toString(),
            site.toString(),customer.toString(),Long.toString(frozen),name(intent),api);
    }
    public static String customerFingerprint(DigestPort digest, ClockOwner owner, UUID operation, String reference) {
        var account = PaymentAccount.create(owner.account(),"clock-owner",PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX,owner.externalAccount(),owner.credentialsRef());
        return StripeRequestFingerprint.of(digest,account,"CUSTOMER_CREATE_OWNED_CLOCK_V1",owner.site().value().toString(),
            owner.customer().value().toString(),owner.run().toString(),owner.intent().toString(),operation.toString(),
            reference,Long.toString(owner.initialFrozenTime()),owner.apiVersion());
    }
}
