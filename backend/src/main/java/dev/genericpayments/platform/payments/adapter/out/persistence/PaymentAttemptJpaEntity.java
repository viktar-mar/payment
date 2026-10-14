package dev.genericpayments.platform.payments.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="payment_attempts")
class PaymentAttemptJpaEntity {
    @Id
    @Column(name="id")
    UUID id;
    @Column(name="payment_id")
    UUID paymentId;
    @Column(name="attempt_number")
    int attemptNumber;
    @Column(name="provider_operation_key")
    String providerOperationKey;
    @Column(name="provider_intent_reference")
    String providerIntentReference;
    @Column(name="payment_method_family")
    String paymentMethodFamily;
    @Column(name="result_code")
    String resultCode;
    @Column(name="status")
    String status;
    @Column(name="created_at")
    Instant createdAt;
    @Column(name="updated_at")
    Instant updatedAt;
    @Version
    @Column(name="version")
    long version;
    protected PaymentAttemptJpaEntity() {}
}

