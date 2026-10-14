package dev.genericpayments.platform.shared.domain;

public final class DomainValidationException extends RuntimeException {
    public DomainValidationException(String message) {
        super(message);
    }
}
