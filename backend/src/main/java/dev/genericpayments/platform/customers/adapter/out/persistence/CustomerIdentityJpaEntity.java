package dev.genericpayments.platform.customers.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.PartitionKey;

@Entity
@Table(name="customer_identities")
class CustomerIdentityJpaEntity {
    @Id UUID id;
    @PartitionKey @Column(name="site_id",updatable=false) UUID siteId;
    @Column(name="customer_id",updatable=false) UUID customerId;
    @Column(updatable=false) String mode;
    @Column(name="issuer_key",length=63,updatable=false) String issuerKey;
    @Column(name="subject_digest",length=512,updatable=false) String subjectDigest;
    @Column(name="subject_digest_key_version",length=16,updatable=false) String subjectDigestKeyVersion;
    @Column(name="verified_at",updatable=false) Instant verifiedAt;
    @Column(name="created_at",updatable=false) Instant createdAt;
    @Column(name="last_used_at") Instant lastUsedAt;
    @Version long version;
    protected CustomerIdentityJpaEntity() {}
}
