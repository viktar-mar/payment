package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;
import java.util.Optional;

public interface CustomerStore {
    Optional<Customer> findById(SiteId siteId, CustomerId id);
    /** Requires an existing transaction; the lock is held until that transaction ends. */
    Optional<Customer> findByIdForUpdate(SiteId siteId, CustomerId id);
    List<Customer> list(SiteId siteId, int limit, int offset);
    List<Customer> query(SiteId siteId, dev.genericpayments.platform.customers.domain.CustomerStatus status,
                         CustomerId customerId, Boolean hasEmail, int limit, int offset);
    /** A non-unique profile hint, never an identity resolution/merge operation. */
    List<Customer> findByEmailDigest(SiteId siteId, String versionedDigest);
    Optional<EncryptedValue> findEncryptedEmail(SiteId siteId, CustomerId id);
    /** Retains email only when both persisted reference and digest are unchanged; absence erases material. */
    Customer save(Customer customer);
    /** Explicit new/replacement email material. The reference must be emailReference(customer.id()). */
    Customer save(Customer customer, EncryptedValue email);
    static String emailReference(CustomerId id) { return "customer-email:" + id.value(); }
}
