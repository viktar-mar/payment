package dev.genericpayments.platform.customers.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.PartitionKey;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="identity_provider_configurations")
class IdentityProviderConfigurationJpaEntity {
    @Id UUID id;
    @PartitionKey @Column(name="site_id",updatable=false) UUID siteId;
    @Column(name="provider_key",length=63,updatable=false) String providerKey;
    String mode;
    String status;
    @Column(length=512) String issuer;
    @JdbcTypeCode(SqlTypes.JSON) String audiences;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="allowed_algorithms") String allowedAlgorithms;
    @Column(name="jwks_source_type",length=16) String jwksSourceType;
    @Column(name="jwks_location",length=512) String jwksLocation;
    @Column(name="public_jwk_set_ref",length=512) String publicJwkSetRef;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="public_jwk_set") String publicJwkSet;
    @Column(name="email_claim",length=512) String emailClaim;
    @Column(name="verified_email_claim",length=512) String verifiedEmailClaim;
    @Column(name="sender_ref",length=512) String senderRef;
    @Column(name="template_key",length=63) String templateKey;
    @Column(name="created_at",updatable=false) Instant createdAt;
    @Column(name="updated_at") Instant updatedAt;
    @Version long version;
    protected IdentityProviderConfigurationJpaEntity() {}
}
