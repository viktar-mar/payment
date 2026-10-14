package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Optional;

public final class Customer {
    private final CustomerId id;
    private final SiteId siteId;
    private final CustomerStatus status;
    private final String encryptedEmailRef;
    private final String emailDigest;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant anonymizedAt;
    private final long version;

    private Customer(CustomerId id, SiteId siteId, CustomerStatus status, String encryptedEmailRef,
                     String emailDigest, Instant createdAt, Instant updatedAt, Instant anonymizedAt, long version) {
        this.id = CustomerDomainRules.required(id, "customer id");
        this.siteId = CustomerDomainRules.required(siteId, "site id");
        this.status = CustomerDomainRules.required(status, "customer status");
        if ((encryptedEmailRef == null) != (emailDigest == null)) {
            throw new DomainValidationException("email reference and digest must be supplied together");
        }
        this.encryptedEmailRef = encryptedEmailRef == null ? null
            : CustomerDomainRules.reference(encryptedEmailRef, "encrypted email");
        this.emailDigest = emailDigest == null ? null : CustomerDomainRules.digest(emailDigest, "email digest");
        this.createdAt = CustomerDomainRules.required(createdAt, "creation time");
        this.updatedAt = CustomerDomainRules.transitionTime(updatedAt, createdAt);
        if (version < 0) throw new DomainValidationException("customer version must not be negative");
        if ((status == CustomerStatus.ANONYMIZED) != (anonymizedAt != null)
            || (status == CustomerStatus.ANONYMIZED && encryptedEmailRef != null)) {
            throw new DomainValidationException("invalid anonymized customer state");
        }
        if (anonymizedAt != null) {
            CustomerDomainRules.transitionTime(anonymizedAt, createdAt);
            CustomerDomainRules.transitionTime(updatedAt, anonymizedAt);
        }
        this.anonymizedAt = anonymizedAt;
        this.version = version;
    }

    public static Customer create(CustomerId id, SiteId siteId, String encryptedEmailRef, String emailDigest, Instant now) {
        return new Customer(id, siteId, CustomerStatus.ACTIVE, encryptedEmailRef, emailDigest, now, now, null, 0);
    }

    /** Validates persisted state without exposing arbitrary aggregate mutation. */
    public static Customer reconstitute(CustomerId id, SiteId siteId, CustomerStatus status, String encryptedEmailRef,
                                        String emailDigest, Instant createdAt, Instant updatedAt, Instant anonymizedAt, long version) {
        return new Customer(id, siteId, status, encryptedEmailRef, emailDigest, createdAt, updatedAt, anonymizedAt, version);
    }

    public Customer touch(Instant now) {
        requireActive();
        CustomerDomainRules.transitionTime(now, updatedAt);
        return new Customer(id, siteId, status, encryptedEmailRef, emailDigest, createdAt, now, null, version);
    }

    public Customer attachEmail(String encryptedEmailRef, String emailDigest, Instant now) {
        requireActive();
        CustomerDomainRules.transitionTime(now, updatedAt);
        CustomerDomainRules.reference(encryptedEmailRef, "encrypted email");
        CustomerDomainRules.digest(emailDigest, "email digest");
        return new Customer(id, siteId, status, encryptedEmailRef, emailDigest, createdAt, now, null, version);
    }

    public Customer anonymize(Instant now) {
        if (status == CustomerStatus.ANONYMIZED) return this;
        CustomerDomainRules.transitionTime(now, updatedAt);
        return new Customer(id, siteId, CustomerStatus.ANONYMIZED, null, null, createdAt, now, now, version);
    }

    private void requireActive() {
        if (status != CustomerStatus.ACTIVE) throw new DomainValidationException("customer must be active");
    }

    public CustomerId id() { return id; }
    public SiteId siteId() { return siteId; }
    public CustomerStatus status() { return status; }
    public Optional<String> encryptedEmailRef() { return Optional.ofNullable(encryptedEmailRef); }
    public Optional<String> emailDigest() { return Optional.ofNullable(emailDigest); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Optional<Instant> anonymizedAt() { return Optional.ofNullable(anonymizedAt); }
    public long version() { return version; }

    @Override
    public String toString() {
        return "Customer[id=" + id + ", siteId=" + siteId + ", status=" + status + "]";
    }
}
