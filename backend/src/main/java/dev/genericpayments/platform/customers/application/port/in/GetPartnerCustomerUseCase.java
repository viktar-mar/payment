package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.model.SafeCustomerView;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;

public interface GetPartnerCustomerUseCase {
    SafeCustomerView getActive(SiteId siteId,CustomerId customerId);
}
