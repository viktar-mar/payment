package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;

public final class CustomerIdentity {
    private final CustomerIdentityId id;
    private final SiteId siteId;
    private final CustomerId customerId;
    private final CustomerAuthenticationMode mode;
    private final String issuerKey;
    private final String subjectDigest;
    private final Instant verifiedAt;
    private final Instant createdAt;
    private final Instant lastUsedAt;

    private CustomerIdentity(CustomerIdentityId id, SiteId siteId, CustomerId customerId,
                             CustomerAuthenticationMode mode, String issuerKey, String subjectDigest,
                             Instant verifiedAt, Instant createdAt, Instant lastUsedAt) {
        this.id = CustomerDomainRules.required(id, "identity id");
        this.siteId = CustomerDomainRules.required(siteId, "site id");
        this.customerId = CustomerDomainRules.required(customerId, "customer id");
        this.mode = CustomerDomainRules.required(mode, "authentication mode");
        this.issuerKey = CustomerDomainRules.safeKey(issuerKey, "issuer key");
        this.subjectDigest = CustomerDomainRules.digest(subjectDigest, "subject digest");
        this.verifiedAt = CustomerDomainRules.required(verifiedAt, "verification time");
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
    }

    public static CustomerIdentity create(CustomerIdentityId id, SiteId siteId, CustomerId customerId,
                                          CustomerAuthenticationMode mode, String issuerKey, String subjectDigest,
                                          Instant now) {
        return new CustomerIdentity(id, siteId, customerId, mode, issuerKey, subjectDigest, now, now, now);
    }

    public static CustomerIdentity reconstitute(CustomerIdentityId id, SiteId siteId, CustomerId customerId,
                                                CustomerAuthenticationMode mode, String issuerKey, String subjectDigest,
                                                Instant verifiedAt, Instant createdAt, Instant lastUsedAt) {
        CustomerDomainRules.required(createdAt, "creation time");
        CustomerDomainRules.transitionTime(verifiedAt, createdAt);
        CustomerDomainRules.transitionTime(lastUsedAt, verifiedAt);
        return new CustomerIdentity(id, siteId, customerId, mode, issuerKey, subjectDigest, verifiedAt, createdAt, lastUsedAt);
    }

    public void assertOwnedBy(CustomerId expectedCustomerId) {
        if (!customerId.equals(expectedCustomerId)) throw new DomainValidationException("identity ownership conflict");
    }

    public void assertOwnedBy(SiteId expectedSiteId, CustomerId expectedCustomerId) {
        if (!siteId.equals(expectedSiteId)) throw new DomainValidationException("identity ownership conflict");
        assertOwnedBy(expectedCustomerId);
    }

    public CustomerIdentity touch(Instant now) {
        CustomerDomainRules.transitionTime(now, lastUsedAt);
        return new CustomerIdentity(id, siteId, customerId, mode, issuerKey, subjectDigest, verifiedAt, createdAt, now);
    }

    public CustomerIdentityId id() { return id; }
    public SiteId siteId() { return siteId; }
    public CustomerId customerId() { return customerId; }
    public CustomerAuthenticationMode mode() { return mode; }
    public String issuerKey() { return issuerKey; }
    public String subjectDigest() { return subjectDigest; }
    public Instant verifiedAt() { return verifiedAt; }
    public Instant createdAt() { return createdAt; }
    public Instant lastUsedAt() { return lastUsedAt; }

    @Override
    public String toString() {
        return "CustomerIdentity[id=" + id + ", siteId=" + siteId + ", customerId=" + customerId + ", mode=" + mode + "]";
    }
}
