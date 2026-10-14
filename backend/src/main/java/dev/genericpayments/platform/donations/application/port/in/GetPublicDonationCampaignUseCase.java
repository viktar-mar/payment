package dev.genericpayments.platform.donations.application.port.in;

import dev.genericpayments.platform.sites.domain.Site;
import java.net.URI;
import java.util.List;

public interface GetPublicDonationCampaignUseCase {
    record PublicCampaign(String key,String displayName,String purposeDescription,String currency,List<Long> suggestedAmounts,
        long minimumAmount,long maximumAmount,boolean customAmountAllowed,boolean anonymousAllowed) {}
    /** Admission shared by preflight and actual reads; ACTIVE Site and exact configured Origin. */
    Site requirePublicSite(String siteKey,URI origin);
    PublicCampaign getPublic(String siteKey,String campaignKey,URI origin);
}
