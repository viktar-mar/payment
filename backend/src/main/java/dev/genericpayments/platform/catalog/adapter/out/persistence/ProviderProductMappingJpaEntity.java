package dev.genericpayments.platform.catalog.adapter.out.persistence;

import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
import dev.genericpayments.platform.catalog.domain.ProductId;
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
@Table(name = "provider_product_mappings")
@IdClass(ProviderProductMappingJpaEntity.Key.class)
class ProviderProductMappingJpaEntity {
    @Id
    @Column(name = "payment_account_id")
    private UUID paymentAccountId;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "provider_product_id", nullable = false, length = 255)
    private String providerProductId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProviderProductMappingJpaEntity() {
    }

    ProviderProductMappingJpaEntity(PaymentAccountId paymentAccountId, ProductId productId,
                                    ProviderProductRef providerProductRef, Instant createdAt) {
        this.paymentAccountId = paymentAccountId.value();
        this.productId = productId.value();
        this.providerProductId = providerProductRef.value();
        this.createdAt = createdAt;
    }

    ProviderProductRef toReference() {
        return new ProviderProductRef(providerProductId);
    }

    static final class Key implements Serializable {
        private UUID paymentAccountId;
        private UUID productId;

        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(paymentAccountId, key.paymentAccountId)
                && Objects.equals(productId, key.productId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(paymentAccountId, productId);
        }
    }
}
