package dev.genericpayments.platform.catalog.application.model;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.time.Instant;
import java.util.Objects;

public record ProviderCatalogMapping(PaymentAccountId paymentAccountId, ProductId productId,
                                     ProviderProductRef productRef, PriceId priceId,
                                     ProviderPriceRef priceRef, Instant createdAt) {
    public ProviderCatalogMapping {
        paymentAccountId = Objects.requireNonNull(paymentAccountId, "paymentAccountId");
        productId = Objects.requireNonNull(productId, "productId");
        productRef = Objects.requireNonNull(productRef, "productRef");
        priceId = Objects.requireNonNull(priceId, "priceId");
        priceRef = Objects.requireNonNull(priceRef, "priceRef");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
