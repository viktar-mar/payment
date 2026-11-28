package dev.genericpayments.platform.provider.application.port.out;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.Optional;
import java.util.UUID;
public interface StripeBillingBindingPort {
    record CustomerBinding(UUID id, PaymentAccountId accountId, SiteId siteId, CustomerId customerId,
                           String providerReference, UUID creationOperationId, long version) {}
    Optional<CustomerBinding> findCustomer(PaymentAccountId accountId, SiteId siteId, CustomerId customerId);
    CustomerBinding bindCustomer(PaymentAccountId accountId, SiteId siteId, CustomerId customerId,
                                 String providerCustomerReference, UUID originalProviderOperationId);
}
