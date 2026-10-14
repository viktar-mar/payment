package dev.genericpayments.platform.donations.application.port.out;

import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.Optional;
import java.util.List;

public interface DonationCampaignStore {
    /** Stable createdAt/id descending pagination within one Site. */
    List<DonationCampaign> list(SiteId siteId,int offset,int limit);
    Optional<DonationCampaign> findById(SiteId siteId,DonationCampaignId id);
    Optional<DonationCampaign> findByKey(SiteId siteId,DonationCampaignKey key);
    /** Requires an existing transaction; lock lasts until it ends. */
    Optional<DonationCampaign> findByIdForUpdate(SiteId siteId,DonationCampaignId id);
    Optional<DonationCampaign> findByKeyForUpdate(SiteId siteId,DonationCampaignKey key);
    DonationCampaign insert(DonationCampaign campaign);
    /** Compare the persisted version with expectedVersion; store the domain version without incrementing it. */
    DonationCampaign update(DonationCampaign campaign,long expectedVersion);
}
