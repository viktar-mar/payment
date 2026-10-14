package dev.genericpayments.platform.admin.application.service;
public final class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() { super("Invalid credentials"); }
}
