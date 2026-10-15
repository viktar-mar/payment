package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;

/** Persisted identity encoding: UTF-16 lengths, exact values, strict UTF-8 SHA-256. */
final class StripeRequestFingerprint {
    private StripeRequestFingerprint() {}

    static String of(DigestPort digest, PaymentAccount account, String... facts) {
        var canonical = new StringBuilder();
        for (String fact : new String[]{account.id().value().toString(), account.externalAccountId(), account.credentialsRef(),
                account.provider().name(), account.mode().name()}) append(canonical, fact);
        for (String fact : facts) append(canonical, fact);
        return digest.sha256Hex(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value == null ? -1 : value.length()).append(':');
        if (value != null) target.append(value);
    }
}
