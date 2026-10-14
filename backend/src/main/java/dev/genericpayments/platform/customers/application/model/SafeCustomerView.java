package dev.genericpayments.platform.customers.application.model;

import dev.genericpayments.platform.customers.domain.*;
import java.time.Instant;
import java.util.*;

public record SafeCustomerView(UUID customerId,UUID siteId,CustomerStatus status,String maskedEmail,
        Instant createdAt,Instant updatedAt,Instant anonymizedAt,List<Identity> identities) {
    public SafeCustomerView { identities=List.copyOf(identities); }
    public record Identity(CustomerAuthenticationMode mode,String issuerKey,Instant createdAt,Instant verifiedAt,Instant lastUsedAt) {}
}
