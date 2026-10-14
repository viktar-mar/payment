package dev.genericpayments.platform.customers.application.port.out;

/** Internal fail-closed signal; the message deliberately contains no identity material. */
public final class SubjectAliasOwnershipConflictException extends RuntimeException {
    public SubjectAliasOwnershipConflictException() {
        super("Identity alias ownership conflict");
    }
}
