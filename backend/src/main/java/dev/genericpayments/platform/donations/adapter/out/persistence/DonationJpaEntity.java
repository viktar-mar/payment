package dev.genericpayments.platform.donations.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="donations")
class DonationJpaEntity {
    @Id UUID id;
    @Column(name="site_id") UUID siteId;
    @Column(name="campaign_id") UUID campaignId;
    @Column(name="message_state") String messageState;
    @Column(name="message_ciphertext") byte[] messageCiphertext;
    @Column(name="message_nonce") byte[] messageNonce;
    @Column(name="message_key_version") String messageKeyVersion;
    @Column(name="created_at") Instant createdAt;
    @Column(name="updated_at") Instant updatedAt;
    protected DonationJpaEntity() {}
}
