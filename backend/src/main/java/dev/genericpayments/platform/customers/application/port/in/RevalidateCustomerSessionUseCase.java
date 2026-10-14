package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.sites.domain.SiteId;

/** Revalidates an already authenticated context in the caller's Site-first transaction. */
public interface RevalidateCustomerSessionUseCase {
    CustomerSessionContext revalidate(CustomerSessionContext context, SiteId site, String origin, CustomerSessionScope scope);
}
