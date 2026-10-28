package dev.genericpayments.platform.payments.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.payments.domain.RefundId;
import java.util.Objects;

/** Transient verified proof; never a provider snapshot or a browser assertion. */
public record ResolvedProviderReceipt(ProviderReferenceReceipt receipt, PaymentId paymentId,
        RefundId refundId, ProviderState state, String requestFingerprint, PaymentAccount verifiedAccount, Payment verifiedPayment) {
    public ResolvedProviderReceipt {
        Objects.requireNonNull(receipt);Objects.requireNonNull(paymentId);Objects.requireNonNull(state);
        Objects.requireNonNull(verifiedAccount);Objects.requireNonNull(verifiedPayment);
        if(requestFingerprint==null||!requestFingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid request fingerprint");
    }
}
