package dev.genericpayments.platform.donations.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="donation_campaigns")
class DonationCampaignJpaEntity {
    @Id UUID id;
    @Column(name="site_id") UUID siteId;
    @Column(name="campaign_key") String campaignKey;
    @Column(name="display_name") String displayName;
    @Column(name="purpose_description") String purposeDescription;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length=3) String currency;
    @Column(name="minimum_amount") long minimumAmount;
    @Column(name="maximum_amount") long maximumAmount;
    @Column(name="custom_amount_allowed") boolean customAmountAllowed;
    @Column(name="anonymous_allowed") boolean anonymousAllowed;
    String status;
    @Column(name="activated_once") boolean activatedOnce;
    // Explicit CAS writes store the already-incremented aggregate version.
    long version;
    @Column(name="created_at") Instant createdAt;
    @Column(name="updated_at") Instant updatedAt;
    @ElementCollection
    @CollectionTable(name="donation_campaign_suggested_amounts",joinColumns=@JoinColumn(name="campaign_id"))
    @OrderColumn(name="position") @Column(name="amount_minor")
    List<Long> suggestedAmounts=new ArrayList<>();
    protected DonationCampaignJpaEntity() {}
}
