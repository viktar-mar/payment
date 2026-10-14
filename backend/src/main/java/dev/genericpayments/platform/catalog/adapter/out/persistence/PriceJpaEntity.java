package dev.genericpayments.platform.catalog.adapter.out.persistence;

import dev.genericpayments.platform.catalog.domain.BillingInterval;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.PriceKey;
import dev.genericpayments.platform.catalog.domain.PriceStatus;
import dev.genericpayments.platform.catalog.domain.PriceType;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.sql.Types;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "prices")
class PriceJpaEntity {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "price_key", nullable = false, length = 63)
    private String key;

    @Column(name = "price_version", nullable = false)
    private int priceVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 16)
    private PriceType type;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(Types.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", length = 16)
    private BillingInterval billingInterval;

    @Column(name = "trial_days", nullable = false)
    private int trialDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "trial_payment_method_policy", nullable = false, length = 32)
    private TrialPaymentMethodPolicy trialPaymentMethodPolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_mode", nullable = false, length = 16)
    private TaxMode taxMode;

    @Column(name = "tax_code", length = 64)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PriceStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PriceJpaEntity() {
    }

    private PriceJpaEntity(Price price) {
        update(price);
    }

    static PriceJpaEntity from(Price price) {
        return new PriceJpaEntity(price);
    }

    void update(Price price) {
        id = price.id().value();
        siteId = price.siteId().value();
        productId = price.productId().value();
        key = price.key().value();
        priceVersion = price.version();
        type = price.type();
        amountMinor = price.money().minorUnits();
        currency = price.money().currency();
        billingInterval = price.billingInterval();
        trialDays = price.trialDays();
        trialPaymentMethodPolicy = price.trialPaymentMethodPolicy();
        taxMode = price.taxMode();
        taxCode = price.taxCode();
        status = price.status();
        publishedAt = price.publishedAt();
        createdAt = price.createdAt();
        updatedAt = price.updatedAt();
    }

    Price toDomain() {
        return new Price(new PriceId(id), new SiteId(siteId), new ProductId(productId), PriceKey.of(key),
            priceVersion, type, Money.of(amountMinor, currency), billingInterval, trialDays, trialPaymentMethodPolicy, taxMode, taxCode,
            status, publishedAt, createdAt, updatedAt);
    }
}
