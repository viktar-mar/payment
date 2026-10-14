package dev.genericpayments.platform.sites.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.AuthenticationMode;
import dev.genericpayments.platform.sites.domain.PaymentMethod;
import dev.genericpayments.platform.sites.domain.PaymentMethodPolicy;
import dev.genericpayments.platform.sites.domain.PaymentMethodPolicyMode;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "sites")
class SiteJpaEntity {
    @Id
    private UUID id;

    @Column(name = "site_key", nullable = false, unique = true, length = 63)
    private String key;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "payment_account_id", nullable = false)
    private UUID paymentAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SiteStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method_policy", nullable = false, length = 16)
    private PaymentMethodPolicyMode paymentMethodPolicy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "site_allowed_origins", joinColumns = @JoinColumn(name = "site_id"))
    @Column(name = "origin", nullable = false, length = 255)
    private Set<String> allowedOrigins = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "site_authentication_modes", joinColumns = @JoinColumn(name = "site_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "authentication_mode", nullable = false, length = 24)
    private Set<AuthenticationMode> authenticationModes = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "site_allowed_payment_methods", joinColumns = @JoinColumn(name = "site_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 24)
    private Set<PaymentMethod> allowedPaymentMethods = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SiteJpaEntity() {
    }

    private SiteJpaEntity(Site site, Instant timestamp) {
        createdAt = timestamp;
        update(site, timestamp);
    }

    static SiteJpaEntity from(Site site, Instant timestamp) {
        return new SiteJpaEntity(site, timestamp);
    }

    void update(Site site, Instant timestamp) {
        id = site.id().value();
        key = site.key().value();
        displayName = site.displayName();
        paymentAccountId = site.paymentAccountId().value();
        status = site.status();
        paymentMethodPolicy = site.paymentMethodPolicy().mode();
        allowedOrigins.clear();
        site.allowedOrigins().stream().map(URI::toString).forEach(allowedOrigins::add);
        authenticationModes.clear();
        authenticationModes.addAll(site.authenticationModes());
        allowedPaymentMethods.clear();
        allowedPaymentMethods.addAll(site.paymentMethodPolicy().allowedMethods());
        updatedAt = timestamp;
    }

    Site toDomain() {
        Set<URI> origins = allowedOrigins.stream().map(URI::create).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Site(new SiteId(id), SiteKey.of(key), displayName, new PaymentAccountId(paymentAccountId),
            status, origins, authenticationModes, new PaymentMethodPolicy(paymentMethodPolicy, allowedPaymentMethods));
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    long version() {
        return version;
    }

    Set<PaymentMethod> allowedPaymentMethods() {
        return Set.copyOf(allowedPaymentMethods);
    }
}
