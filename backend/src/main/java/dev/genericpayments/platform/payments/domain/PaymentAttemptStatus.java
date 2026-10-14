package dev.genericpayments.platform.payments.domain;

public enum PaymentAttemptStatus {
    CREATED, REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION, PROCESSING,
    SUCCEEDED, FAILED, UNKNOWN, CANCELLED
}
