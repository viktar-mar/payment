package dev.genericpayments.platform.payments.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.payments.domain.Refund;
import java.util.Objects;

/** Transient individual-refund request proof, rechecked against locked facts before settlement. */
public record VerifiedRefundProof(PaymentAccount account,Payment payment,Refund refund,String requestFingerprint) {
    public VerifiedRefundProof {
        Objects.requireNonNull(account);Objects.requireNonNull(payment);Objects.requireNonNull(refund);
        if(requestFingerprint==null||!requestFingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid request fingerprint");
    }
}
