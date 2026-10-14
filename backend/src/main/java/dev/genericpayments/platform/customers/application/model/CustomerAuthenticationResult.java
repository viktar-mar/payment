package dev.genericpayments.platform.customers.application.model;

import dev.genericpayments.platform.customers.domain.CustomerId;
import java.time.Instant;
import java.util.Objects;

public sealed interface CustomerAuthenticationResult permits CustomerAuthenticationResult.AuthenticatedCustomer,CustomerAuthenticationResult.LinkProofIssued {
    record AuthenticatedCustomer(CustomerId customerId,CustomerSessionCreation session) implements CustomerAuthenticationResult {
        public AuthenticatedCustomer {
            Objects.requireNonNull(customerId,"customer id");
            Objects.requireNonNull(session,"session");
        }
    }
    record LinkProofIssued(String proof,Instant expiresAt) implements CustomerAuthenticationResult {
        public LinkProofIssued { Objects.requireNonNull(proof,"proof"); Objects.requireNonNull(expiresAt,"expiry"); }
        @Override public String toString() { return "LinkProofIssued[redacted]"; }
    }
}
