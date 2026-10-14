package dev.genericpayments.platform.donations.domain;

/** Immutable checkout context; subsequent Campaign edits cannot change it. */
public record DonationCampaignSnapshot(DonationCampaignId campaignId, DonationCampaignKey campaignKey,
                                       long version, String currency) {
    public DonationCampaignSnapshot {
        DonationDomainRules.required(campaignId, "snapshot campaign id");
        DonationDomainRules.required(campaignKey, "snapshot campaign key");
        DonationDomainRules.version(version);
        currency = DonationDomainRules.currency(currency);
    }
}
