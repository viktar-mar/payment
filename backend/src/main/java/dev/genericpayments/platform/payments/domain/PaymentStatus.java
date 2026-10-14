package dev.genericpayments.platform.payments.domain;

public enum PaymentStatus {
    CREATED, REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION, PROCESSING,
    SUCCEEDED, FAILED, CANCELLED, PARTIALLY_REFUNDED, REFUNDED
}
