package dev.genericpayments.platform.catalog.adapter.out.persistence;

import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "provider_price_mappings")
@IdClass(ProviderPriceMappingJpaEntity.Key.class)
class ProviderPriceMappingJpaEntity {
    @Id
    @Column(name = "payment_account_id")
    private UUID paymentAccountId;

    @Id
    @Column(name = "price_id")
    private UUID priceId;

    @Column(name = "provider_price_id", nullable = false, length = 255)
    private String providerPriceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProviderPriceMappingJpaEntity() {
    }

    ProviderPriceMappingJpaEntity(PaymentAccountId paymentAccountId, PriceId priceId,
                                  ProviderPriceRef providerPriceRef, Instant createdAt) {
        this.paymentAccountId = paymentAccountId.value();
        this.priceId = priceId.value();
        this.providerPriceId = providerPriceRef.value();
        this.createdAt = createdAt;
    }

    ProviderPriceRef toReference() {
        return new ProviderPriceRef(providerPriceId);
    }

    static final class Key implements Serializable {
        private UUID paymentAccountId;
        private UUID priceId;

        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(paymentAccountId, key.paymentAccountId)
                && Objects.equals(priceId, key.priceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(paymentAccountId, priceId);
        }
    }
}
