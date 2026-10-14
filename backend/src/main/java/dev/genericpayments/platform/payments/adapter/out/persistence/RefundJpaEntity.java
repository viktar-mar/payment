package dev.genericpayments.platform.payments.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="refunds")
class RefundJpaEntity {
    @Id
    @Column(name="id")
    UUID id;
    @Column(name="payment_id")
    UUID paymentId;
    @Column(name="amount_minor")
    long amountMinor;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="currency", length=3)
    String currency;
    @Column(name="status")
    String status;
    @Column(name="provider_operation_key")
    String providerOperationKey;
    @Column(name="provider_refund_reference")
    String providerRefundReference;
    @Column(name="reason_code")
    String reasonCode;
    @Column(name="technical_note")
    String technicalNote;
    @Column(name="created_at")
    Instant createdAt;
    @Column(name="updated_at")
    Instant updatedAt;
    @Version
    @Column(name="version")
    long version;
    protected RefundJpaEntity() {}
}

