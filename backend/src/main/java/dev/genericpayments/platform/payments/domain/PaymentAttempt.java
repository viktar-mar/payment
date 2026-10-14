package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;

public record PaymentAttempt(PaymentAttemptId id, PaymentId paymentId, int sequence, String providerOperationKey,
                             String providerIntentReference, String paymentMethodFamily, PaymentAttemptStatus status,
                             String resultCode, Instant createdAt, Instant updatedAt) {
    public PaymentAttempt {
        PaymentDomainRules.required(id, "payment attempt id");
        PaymentDomainRules.required(paymentId, "payment id");
        if (sequence <= 0) throw new DomainValidationException("attempt sequence must be positive");
        PaymentDomainRules.text(providerOperationKey, "provider operation key", 255);
        PaymentDomainRules.optionalText(providerIntentReference, "provider intent reference", 255);
        PaymentDomainRules.code(paymentMethodFamily, "payment method family");
        PaymentDomainRules.required(status, "attempt status");
        PaymentDomainRules.code(resultCode, "attempt result code");
        PaymentDomainRules.timestamps(createdAt, updatedAt);
    }

    public static PaymentAttempt start(PaymentAttemptId id, PaymentId paymentId, int sequence,
                                       String providerOperationKey, String paymentMethodFamily, Instant now) {
        return new PaymentAttempt(id, paymentId, sequence, providerOperationKey, null, paymentMethodFamily,
            PaymentAttemptStatus.CREATED, null, now, now);
    }

    public boolean blocksSubsequentAttempt() { return status == PaymentAttemptStatus.UNKNOWN; }

    public PaymentAttempt requiresPaymentMethod(Instant now) { return transition(PaymentAttemptStatus.REQUIRES_PAYMENT_METHOD, null, now); }
    public PaymentAttempt requiresAction(Instant now) { return transition(PaymentAttemptStatus.REQUIRES_ACTION, null, now); }
    public PaymentAttempt processing(Instant now) { return transition(PaymentAttemptStatus.PROCESSING, null, now); }
    public PaymentAttempt succeeded(Instant now) { return transition(PaymentAttemptStatus.SUCCEEDED, null, now); }
    public PaymentAttempt failed(String code, Instant now) { return transition(PaymentAttemptStatus.FAILED, code, now); }
    public PaymentAttempt unknown(String code, Instant now) { return transition(PaymentAttemptStatus.UNKNOWN, code, now); }
    public PaymentAttempt cancelled(Instant now) { return transition(PaymentAttemptStatus.CANCELLED, null, now); }

    public PaymentAttempt withProviderIntentReference(String reference, Instant now) {
        PaymentDomainRules.text(reference, "provider intent reference", 255);
        PaymentDomainRules.time(now, updatedAt);
        if (providerIntentReference != null && !providerIntentReference.equals(reference)) {
            throw new DomainValidationException("provider intent reference cannot be replaced");
        }
        return reference.equals(providerIntentReference) ? this : copy(status, reference, resultCode, now);
    }

    private PaymentAttempt transition(PaymentAttemptStatus target, String code, Instant now) {
        PaymentDomainRules.time(now, updatedAt);
        PaymentDomainRules.code(code, "attempt result code");
        if (status == target) return this;
        boolean allowed = switch (status) {
            case SUCCEEDED, FAILED, CANCELLED -> false;
            case UNKNOWN -> target != PaymentAttemptStatus.CREATED;
            default -> switch (target) {
                case REQUIRES_PAYMENT_METHOD -> status == PaymentAttemptStatus.CREATED;
                case REQUIRES_ACTION -> status == PaymentAttemptStatus.CREATED || status == PaymentAttemptStatus.REQUIRES_PAYMENT_METHOD;
                case PROCESSING, SUCCEEDED, FAILED, UNKNOWN, CANCELLED -> true;
                default -> false;
            };
        };
        if (!allowed) throw new DomainValidationException("attempt cannot transition from " + status + " to " + target);
        return copy(target, providerIntentReference, code, now);
    }

    private PaymentAttempt copy(PaymentAttemptStatus nextStatus, String reference, String code, Instant now) {
        return new PaymentAttempt(id, paymentId, sequence, providerOperationKey, reference, paymentMethodFamily,
            nextStatus, code, createdAt, now);
    }
}
