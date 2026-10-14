package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;

public record Refund(RefundId id, PaymentId paymentId, Money money, RefundStatus status,
                     String providerRefundReference, String providerOperationKey, String reasonCode,
                     String technicalNote, Instant createdAt, Instant updatedAt) {
    public Refund {
        PaymentDomainRules.required(id, "refund id");
        PaymentDomainRules.required(paymentId, "payment id");
        PaymentDomainRules.required(money, "refund money");
        PaymentDomainRules.required(status, "refund status");
        PaymentDomainRules.optionalText(providerRefundReference, "provider refund reference", 255);
        PaymentDomainRules.text(providerOperationKey, "provider operation key", 255);
        PaymentDomainRules.code(reasonCode, "refund reason code");
        PaymentDomainRules.optionalText(technicalNote, "refund technical note", 500);
        if (technicalNote != null) PaymentDomainRules.safeTechnicalValue(technicalNote);
        PaymentDomainRules.timestamps(createdAt, updatedAt);
    }

    public static Refund request(RefundId id, Payment payment, Money amount, String providerOperationKey,
                                 String reasonCode, String technicalNote, Instant now) {
        PaymentDomainRules.required(payment, "payment");
        PaymentDomainRules.required(amount, "refund amount");
        if (!payment.money().currency().equals(amount.currency())) {
            throw new DomainValidationException("refund currency must match payment currency");
        }
        if (!payment.canRefund()) throw new DomainValidationException("payment is not refundable");
        if (amount.minorUnits() > payment.refundableAmountMinor()) {
            throw new DomainValidationException("refund amount exceeds remaining refundable amount");
        }
        PaymentDomainRules.time(now, payment.updatedAt());
        return new Refund(id, payment.id(), amount, RefundStatus.PENDING, null, providerOperationKey,
            reasonCode, technicalNote, now, now);
    }

    public Refund processing(Instant now) { return transition(RefundStatus.PROCESSING, now); }
    public Refund succeeded(Instant now) { return transition(RefundStatus.SUCCEEDED, now); }
    public Refund failed(Instant now) { return transition(RefundStatus.FAILED, now); }
    public Refund unknown(Instant now) { return transition(RefundStatus.UNKNOWN, now); }
    public Refund cancelled(Instant now) { return transition(RefundStatus.CANCELLED, now); }

    public Refund withProviderRefundReference(String reference, Instant now) {
        PaymentDomainRules.text(reference, "provider refund reference", 255);
        PaymentDomainRules.time(now, updatedAt);
        if (providerRefundReference != null && !providerRefundReference.equals(reference)) {
            throw new DomainValidationException("provider refund reference cannot be replaced");
        }
        return reference.equals(providerRefundReference) ? this : copy(status, reference, now);
    }

    private Refund transition(RefundStatus target, Instant now) {
        PaymentDomainRules.time(now, updatedAt);
        if (status == target) return this;
        if (status == RefundStatus.SUCCEEDED || status == RefundStatus.FAILED || status == RefundStatus.CANCELLED) {
            throw new DomainValidationException("refund cannot transition from " + status + " to " + target);
        }
        return copy(target, providerRefundReference, now);
    }

    private Refund copy(RefundStatus nextStatus, String reference, Instant now) {
        return new Refund(id, paymentId, money, nextStatus, reference, providerOperationKey, reasonCode,
            technicalNote, createdAt, now);
    }
}
