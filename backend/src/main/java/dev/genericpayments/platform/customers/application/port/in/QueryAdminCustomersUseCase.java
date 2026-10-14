package dev.genericpayments.platform.customers.application.port.in;

import dev.genericpayments.platform.customers.application.model.SafeCustomerView;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;

public interface QueryAdminCustomersUseCase {
    record Query(SiteId siteId,CustomerStatus status,CustomerId customerId,String maskedEmail,int limit,int offset) {}
    record Page(List<SafeCustomerView> items) { public Page {items=List.copyOf(items);} }
    Page list(Query query);
    SafeCustomerView get(SiteId siteId,CustomerId customerId);
}
