package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;

/** Persisted identity encoding: UTF-16 lengths, exact values, strict UTF-8 SHA-256. */
public final class StripeRequestFingerprint {
    private StripeRequestFingerprint() {}

    public static String of(DigestPort digest, PaymentAccount account, String... facts) {
        return dev.genericpayments.platform.provider.application.model.ProviderRequestFingerprint.of(digest,account,facts);
    }
}
