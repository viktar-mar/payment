package dev.genericpayments.platform.customers.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.PartitionKey;

@Entity
@Table(name="customers")
class CustomerJpaEntity {
    @Id UUID id;
    @PartitionKey @Column(name="site_id",updatable=false) UUID siteId;
    String status;
    @Column(name="email_ciphertext") byte[] emailCiphertext;
    @Column(name="email_nonce") byte[] emailNonce;
    @Column(name="email_key_version",length=128) String emailKeyVersion;
    @Column(name="email_digest",length=512) String emailDigest;
    @Column(name="email_digest_key_version",length=16) String emailDigestKeyVersion;
    @Column(name="created_at",updatable=false) Instant createdAt;
    @Column(name="updated_at") Instant updatedAt;
    @Column(name="anonymized_at") Instant anonymizedAt;
    @Version long version;
    protected CustomerJpaEntity() {}
}
