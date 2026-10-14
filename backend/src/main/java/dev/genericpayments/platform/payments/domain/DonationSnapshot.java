package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.donations.domain.DonationCampaignId;
import dev.genericpayments.platform.donations.domain.DonationCampaignKey;
import dev.genericpayments.platform.donations.domain.DonationCampaignSnapshot;

/** Immutable campaign context; contains no donor message or identity. */
public record DonationSnapshot(DonationCampaignId campaignId, DonationCampaignKey campaignKey,
                               long version, String currency) {
    public DonationSnapshot {
        DonationCampaignSnapshot validated=new DonationCampaignSnapshot(campaignId,campaignKey,version,currency);
        currency=validated.currency();
    }
}
