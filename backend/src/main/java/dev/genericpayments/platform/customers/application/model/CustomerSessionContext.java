package dev.genericpayments.platform.customers.application.model;

import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public record CustomerSessionContext(CustomerSessionId sessionId,SiteId siteId,CustomerId customerId,
        String origin,Set<CustomerSessionScope> scopes,Instant expiresAt) {
    public CustomerSessionContext {
        Objects.requireNonNull(sessionId,"session id"); Objects.requireNonNull(siteId,"site id");
        Objects.requireNonNull(customerId,"customer id"); Objects.requireNonNull(origin,"origin");
        scopes=Set.copyOf(scopes); Objects.requireNonNull(expiresAt,"expiry");
    }

    public void requireScope(CustomerSessionScope required) {
        Objects.requireNonNull(required,"required scope");
        if(!scopes.contains(required)) throw new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");
    }
}
