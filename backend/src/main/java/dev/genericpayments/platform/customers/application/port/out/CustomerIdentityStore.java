package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.customers.domain.CustomerIdentity;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;
import java.util.Optional;

public interface CustomerIdentityStore {
    record SubjectAlias(String versionedSubjectDigest, CustomerIdentity identity) {}
    Optional<CustomerIdentity> find(SiteId siteId, String issuerKey, String versionedSubjectDigest);
    /** Requires an existing transaction; an absent identity is protected by the unique constraint on insert. */
    Optional<CustomerIdentity> findForUpdate(SiteId siteId, String issuerKey, String versionedSubjectDigest);
    /** Serializes the complete alias set across nodes, then resolves every matching alias in the caller's transaction. */
    List<SubjectAlias> reserveSubjectAliases(SiteId siteId, String issuerKey, List<String> versionedSubjectDigests);
    /** Creates one semantic identity and its complete, explicitly supplied alias set atomically. */
    CustomerIdentity create(CustomerIdentity identity, List<String> versionedSubjectDigests);
    /** Updates the safe mutable facts of an existing semantic identity. */
    CustomerIdentity update(CustomerIdentity identity);
    /** Backfills aliases for one already-persisted semantic identity using the same cross-node reservation protocol. */
    void saveSubjectAliases(CustomerIdentity identity, List<String> versionedSubjectDigests);
    List<CustomerIdentity> list(SiteId siteId, CustomerId customerId);
    void deleteByCustomer(SiteId siteId, CustomerId customerId);
}
