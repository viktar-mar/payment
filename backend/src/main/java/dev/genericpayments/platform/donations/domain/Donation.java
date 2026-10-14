package dev.genericpayments.platform.donations.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Optional;

/** Donation context only. Financial state and Customer ownership belong to the linked Payment. */
public final class Donation {
    private final DonationId id;
    private final SiteId siteId;
    private final DonationCampaignId campaignId;
    private final String encryptedMessageRef;
    private final DonorMessageState messageState;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Donation(DonationId id, SiteId siteId, DonationCampaignId campaignId, String encryptedMessageRef,
                     DonorMessageState messageState, Instant createdAt, Instant updatedAt) {
        this.id = DonationDomainRules.required(id, "donation id");
        this.siteId = DonationDomainRules.required(siteId, "site id");
        this.campaignId = DonationDomainRules.required(campaignId, "campaign id");
        this.messageState = DonationDomainRules.required(messageState, "message state");
        if ((messageState == DonorMessageState.PRESENT) != (encryptedMessageRef != null)
            || (encryptedMessageRef != null && encryptedMessageRef.isBlank())) {
            throw new DomainValidationException("message state does not match encrypted reference");
        }
        this.encryptedMessageRef = encryptedMessageRef;
        this.createdAt = DonationDomainRules.required(createdAt, "creation time");
        this.updatedAt = DonationDomainRules.transitionTime(updatedAt, createdAt);
    }

    public static Donation create(DonationId id, SiteId siteId, DonationCampaignId campaignId,
                                   String encryptedMessageRef, Instant now) {
        return new Donation(id, siteId, campaignId, encryptedMessageRef,
            encryptedMessageRef == null ? DonorMessageState.ABSENT : DonorMessageState.PRESENT, now, now);
    }

    public static Donation reconstitute(DonationId id, SiteId siteId, DonationCampaignId campaignId,
                                         String encryptedMessageRef, DonorMessageState messageState,
                                         Instant createdAt, Instant updatedAt) {
        return new Donation(id, siteId, campaignId, encryptedMessageRef, messageState, createdAt, updatedAt);
    }

    public Donation redactMessage(Instant now) {
        if (messageState != DonorMessageState.PRESENT) return this;
        DonationDomainRules.transitionTime(now, updatedAt);
        return new Donation(id, siteId, campaignId, null, DonorMessageState.REDACTED, createdAt, now);
    }

    public DonationId id() { return id; }
    public SiteId siteId() { return siteId; }
    public DonationCampaignId campaignId() { return campaignId; }
    public Optional<String> encryptedMessageRef() { return Optional.ofNullable(encryptedMessageRef); }
    public DonorMessageState messageState() { return messageState; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Donation[id=" + id + ", siteId=" + siteId + ", campaignId=" + campaignId
            + ", messageState=" + messageState + "]";
    }
}
