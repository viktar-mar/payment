package dev.genericpayments.platform.customers.adapter.out.security;

import dev.genericpayments.platform.shared.application.PaymentApplicationException;

final class CustomerAuthenticationFailure {
    private CustomerAuthenticationFailure() {}
    static PaymentApplicationException failed() {
        // Never attach a parser, DNS, TLS, HTTP, or JOSE exception: its message may carry credentials or PII.
        return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");
    }
}
