package dev.genericpayments.platform.customers.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.PartitionKey;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="customer_sessions")
class CustomerSessionJpaEntity {
    @Id UUID id;
    @PartitionKey @Column(name="site_id",updatable=false) UUID siteId;
    @Column(name="customer_id",updatable=false) UUID customerId;
    @Column(length=2048,updatable=false) String origin;
    @Column(name="token_digest",length=512,updatable=false) String tokenDigest;
    @Column(name="token_digest_key_version",length=16,updatable=false) String tokenDigestKeyVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb",updatable=false) String scopes;
    @Column(name="issued_at",updatable=false) Instant issuedAt;
    @Column(name="last_used_at") Instant lastUsedAt;
    @Column(name="expires_at",updatable=false) Instant expiresAt;
    @Column(name="revoked_at") Instant revokedAt;
    @Column(name="revoked_reason",length=32) String revokedReason;
    @Version long version;
    protected CustomerSessionJpaEntity() {}
}
