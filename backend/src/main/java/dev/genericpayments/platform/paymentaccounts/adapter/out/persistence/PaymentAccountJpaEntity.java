package dev.genericpayments.platform.paymentaccounts.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_accounts")
class PaymentAccountJpaEntity {
    @Id
    private UUID id;

    @Column(name = "account_key", nullable = false, unique = true, length = 63)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentProviderType provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_mode", nullable = false, length = 16)
    private PaymentAccountMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 16)
    private PaymentAccountStatus status;

    @Column(name = "external_account_id", nullable = false, length = 255)
    private String externalAccountId;

    @Column(name = "credentials_ref", nullable = false, length = 255)
    private String credentialsRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected PaymentAccountJpaEntity() {
    }

    private PaymentAccountJpaEntity(PaymentAccount account, Instant createdAt) {
        this.createdAt = createdAt;
        update(account);
    }

    static PaymentAccountJpaEntity from(PaymentAccount account, Instant createdAt) {
        return new PaymentAccountJpaEntity(account, createdAt);
    }

    void update(PaymentAccount account) {
        id = account.id().value();
        key = account.key();
        provider = account.provider();
        mode = account.mode();
        status = account.status();
        externalAccountId = account.externalAccountId();
        credentialsRef = account.credentialsRef();
    }

    PaymentAccount toDomain() {
        return new PaymentAccount(new PaymentAccountId(id), key, provider, mode, status,
            externalAccountId, credentialsRef);
    }

    Instant createdAt() {
        return createdAt;
    }

    long version() {
        return version;
    }
}
