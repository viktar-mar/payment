package dev.genericpayments.platform.catalog.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Objects;

public record Price(PriceId id, SiteId siteId, ProductId productId, PriceKey key, int version,
                    PriceType type, Money money, BillingInterval billingInterval, int trialDays,
                    TrialPaymentMethodPolicy trialPaymentMethodPolicy, TaxMode taxMode, String taxCode,
                    PriceStatus status, Instant publishedAt,
                    Instant createdAt, Instant updatedAt) {
    public Price {
        id = Objects.requireNonNull(id, "id");
        siteId = Objects.requireNonNull(siteId, "siteId");
        productId = Objects.requireNonNull(productId, "productId");
        key = Objects.requireNonNull(key, "key");
        if (version <= 0) {
            throw new DomainValidationException("price version must be positive");
        }
        type = Objects.requireNonNull(type, "type");
        money = Objects.requireNonNull(money, "money");
        trialPaymentMethodPolicy = Objects.requireNonNull(trialPaymentMethodPolicy, "trialPaymentMethodPolicy");
        taxMode = Objects.requireNonNull(taxMode, "taxMode");
        status = Objects.requireNonNull(status, "status");
        validateBilling(type, billingInterval, trialDays);
        if (status == PriceStatus.PUBLISHED && publishedAt == null) {
            throw new DomainValidationException("published price requires published at");
        }
    }

    public Price(PriceId id, SiteId siteId, ProductId productId, PriceKey key, int version,
                 PriceType type, Money money, BillingInterval billingInterval, int trialDays,
                 TaxMode taxMode, String taxCode, PriceStatus status, Instant publishedAt,
                 Instant createdAt, Instant updatedAt) {
        this(id, siteId, productId, key, version, type, money, billingInterval, trialDays,
            TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL, taxMode, taxCode, status, publishedAt, createdAt, updatedAt);
    }

    public static Price draft(PriceId id, SiteId siteId, ProductId productId, PriceKey key, int version,
                              PriceType type, Money money, BillingInterval billingInterval, int trialDays,
                              TaxMode taxMode, String taxCode) {
        return new Price(id, siteId, productId, key, version, type, money, billingInterval, trialDays,
            taxMode, taxCode, PriceStatus.DRAFT, null, null, null);
    }

    public static Price draft(PriceId id, SiteId siteId, ProductId productId, PriceKey key, int version,
                              PriceType type, Money money, BillingInterval billingInterval, int trialDays,
                              TaxMode taxMode, String taxCode, Instant createdAt) {
        return new Price(id, siteId, productId, key, version, type, money, billingInterval, trialDays,
            taxMode, taxCode, PriceStatus.DRAFT, null, createdAt, createdAt);
    }

    public static Price draft(PriceId id, SiteId siteId, ProductId productId, PriceKey key, int version,
                              PriceType type, Money money, BillingInterval billingInterval, int trialDays,
                              TrialPaymentMethodPolicy trialPolicy, TaxMode taxMode, String taxCode) {
        return draft(id, siteId, productId, key, version, type, money, billingInterval, trialDays,
            trialPolicy, taxMode, taxCode, null);
    }

    public static Price draft(PriceId id, SiteId siteId, ProductId productId, PriceKey key, int version,
                              PriceType type, Money money, BillingInterval billingInterval, int trialDays,
                              TrialPaymentMethodPolicy trialPolicy, TaxMode taxMode, String taxCode, Instant createdAt) {
        return new Price(id, siteId, productId, key, version, type, money, billingInterval, trialDays,
            trialPolicy, taxMode, taxCode, PriceStatus.DRAFT, null, createdAt, createdAt);
    }

    public Price publish(Instant at) {
        requireDraft();
        Instant timestamp = Objects.requireNonNull(at, "publishedAt");
        return new Price(id, siteId, productId, key, version, type, money, billingInterval, trialDays,
            trialPaymentMethodPolicy, taxMode, taxCode, PriceStatus.PUBLISHED, timestamp, createdAt, timestamp);
    }

    public Price changeAmount(Money replacement) {
        requireDraft();
        return new Price(id, siteId, productId, key, version, type, replacement, billingInterval, trialDays,
            trialPaymentMethodPolicy, taxMode, taxCode, status, publishedAt, createdAt, updatedAt);
    }

    public Price withTrialPaymentMethodPolicy(TrialPaymentMethodPolicy replacement) {
        requireDraft();
        return new Price(id, siteId, productId, key, version, type, money, billingInterval, trialDays,
            replacement, taxMode, taxCode, status, publishedAt, createdAt, updatedAt);
    }

    private void requireDraft() {
        if (status != PriceStatus.DRAFT) {
            throw new DomainValidationException("published price is immutable");
        }
    }

    private static void validateBilling(PriceType type, BillingInterval interval, int trialDays) {
        if (trialDays < 0) {
            throw new DomainValidationException("trial days must not be negative");
        }
        if (type == PriceType.ONE_TIME && interval != null) {
            throw new DomainValidationException("one-time price cannot have a billing interval");
        }
        if (type == PriceType.ONE_TIME && trialDays != 0) {
            throw new DomainValidationException("one-time price cannot have trial days");
        }
        if (type == PriceType.SUBSCRIPTION && interval == null) {
            throw new DomainValidationException("subscription price requires a billing interval");
        }
    }
}
