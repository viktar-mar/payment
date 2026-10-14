package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import java.time.Instant;
import java.util.*;

public interface GetCurrentCustomerUseCase {
    CurrentCustomer get(CustomerSessionContext session);
    record CurrentCustomer(CustomerId id,CustomerStatus status,Optional<String> maskedEmail,
            Set<CustomerAuthenticationMode> authenticationModes,Instant sessionExpiresAt) {
        public CurrentCustomer { maskedEmail=Objects.requireNonNull(maskedEmail,"masked email"); authenticationModes=Set.copyOf(authenticationModes); }
    }
}
