package dev.genericpayments.platform.donations.application.port.in;

import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;

public interface ManageDonationCampaignUseCase {
    record Configuration(String key,String displayName,String purposeDescription,String currency,
        List<Long> suggestedAmounts,long minimumAmount,long maximumAmount,boolean customAmountAllowed,boolean anonymousAllowed) {}
    /** A replay returns the current Site-owned projection without another mutation/audit. */
    record Mutation(DonationCampaign campaign,boolean replayed) {}
    List<DonationCampaign> list(SiteId site,int offset,int limit);
    DonationCampaign get(SiteId site,DonationCampaignId id);
    Mutation create(SiteId site,UUID actor,String idempotencyKey,Configuration configuration);
    Mutation update(SiteId site,DonationCampaignId id,UUID actor,String idempotencyKey,long expectedVersion,Configuration configuration);
    Mutation changeStatus(SiteId site,DonationCampaignId id,UUID actor,String idempotencyKey,long expectedVersion,CampaignStatus status);
}
