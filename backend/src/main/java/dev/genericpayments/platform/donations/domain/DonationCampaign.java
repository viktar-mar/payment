package dev.genericpayments.platform.donations.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

public final class DonationCampaign {
    private final DonationCampaignId id;
    private final SiteId siteId;
    private final DonationCampaignKey campaignKey;
    private final String displayName;
    private final String purposeDescription;
    private final String currency;
    private final List<Long> suggestedAmounts;
    private final long minimumAmount;
    private final long maximumAmount;
    private final boolean customAmountAllowed;
    private final boolean anonymousAllowed;
    private final CampaignStatus status;
    private final boolean activatedOnce;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private DonationCampaign(DonationCampaignId id, SiteId siteId, DonationCampaignKey campaignKey,
                             String displayName, String purposeDescription, String currency,
                             List<Long> suggestedAmounts, long minimumAmount, long maximumAmount,
                             boolean customAmountAllowed, boolean anonymousAllowed, CampaignStatus status,
                             boolean activatedOnce, long version, Instant createdAt, Instant updatedAt) {
        this.id = DonationDomainRules.required(id, "campaign id");
        this.siteId = DonationDomainRules.required(siteId, "site id");
        this.campaignKey = DonationDomainRules.required(campaignKey, "campaign key");
        if (displayName == null || displayName.isBlank()) {
            throw new DomainValidationException("campaign display name must not be blank");
        }
        this.displayName = displayName;
        this.purposeDescription = purposeDescription;
        this.currency = DonationDomainRules.currency(currency);
        if (minimumAmount <= 0 || maximumAmount <= 0 || minimumAmount > maximumAmount) {
            throw new DomainValidationException("campaign amount bounds must be positive and ordered");
        }
        DonationDomainRules.required(suggestedAmounts, "suggested amounts");
        HashSet<Long> unique = new HashSet<>();
        for (Long amount : suggestedAmounts) {
            if (amount == null || amount < minimumAmount || amount > maximumAmount || !unique.add(amount)) {
                throw new DomainValidationException("suggested amounts must be unique and within campaign bounds");
            }
        }
        if (!customAmountAllowed && suggestedAmounts.isEmpty()) {
            throw new DomainValidationException("suggested-only campaign requires suggested amounts");
        }
        this.suggestedAmounts = List.copyOf(suggestedAmounts);
        this.minimumAmount = minimumAmount;
        this.maximumAmount = maximumAmount;
        this.customAmountAllowed = customAmountAllowed;
        this.anonymousAllowed = anonymousAllowed;
        this.status = DonationDomainRules.required(status, "campaign status");
        if ((status == CampaignStatus.DRAFT && activatedOnce)
            || ((status == CampaignStatus.ACTIVE || status == CampaignStatus.PAUSED) && !activatedOnce)) {
            throw new DomainValidationException("campaign activation history does not match lifecycle state");
        }
        this.activatedOnce = activatedOnce;
        DonationDomainRules.version(version);
        this.version = version;
        this.createdAt = DonationDomainRules.required(createdAt, "creation time");
        this.updatedAt = DonationDomainRules.transitionTime(updatedAt, createdAt);
    }

    public static DonationCampaign draft(DonationCampaignId id, SiteId siteId, DonationCampaignKey campaignKey,
                                         String displayName, String purposeDescription, String currency,
                                         List<Long> suggestedAmounts, long minimumAmount, long maximumAmount,
                                         boolean customAmountAllowed, boolean anonymousAllowed, Instant now) {
        return new DonationCampaign(id, siteId, campaignKey, displayName, purposeDescription, currency,
            suggestedAmounts, minimumAmount, maximumAmount, customAmountAllowed, anonymousAllowed,
            CampaignStatus.DRAFT, false, 0, now, now);
    }

    /** Validates persisted state without exposing arbitrary aggregate mutation. */
    public static DonationCampaign reconstitute(DonationCampaignId id, SiteId siteId, DonationCampaignKey campaignKey,
                                                String displayName, String purposeDescription, String currency,
                                                List<Long> suggestedAmounts, long minimumAmount, long maximumAmount,
                                                boolean customAmountAllowed, boolean anonymousAllowed, CampaignStatus status,
                                                boolean activatedOnce, long version, Instant createdAt, Instant updatedAt) {
        return new DonationCampaign(id, siteId, campaignKey, displayName, purposeDescription, currency,
            suggestedAmounts, minimumAmount, maximumAmount, customAmountAllowed, anonymousAllowed,
            status, activatedOnce, version, createdAt, updatedAt);
    }

    public DonationCampaign activate(Instant now) {
        if (status != CampaignStatus.DRAFT && status != CampaignStatus.PAUSED) {
            throw new DomainValidationException("only draft or paused campaign can activate");
        }
        return transition(CampaignStatus.ACTIVE, true, now);
    }

    public DonationCampaign pause(Instant now) {
        if (status != CampaignStatus.ACTIVE) throw new DomainValidationException("only active campaign can pause");
        return transition(CampaignStatus.PAUSED, activatedOnce, now);
    }

    public DonationCampaign archive(Instant now) {
        requireNotArchived();
        return transition(CampaignStatus.ARCHIVED, activatedOnce, now);
    }

    public DonationCampaign updateConfiguration(String displayName, String purposeDescription, String currency,
                                                 List<Long> suggestedAmounts, long minimumAmount, long maximumAmount,
                                                 boolean customAmountAllowed, boolean anonymousAllowed, Instant now) {
        requireNotArchived();
        DonationDomainRules.transitionTime(now, updatedAt);
        String normalizedCurrency = DonationDomainRules.currency(currency);
        if (activatedOnce && !this.currency.equals(normalizedCurrency)) {
            throw new DomainValidationException("campaign currency is immutable after first activation");
        }
        return new DonationCampaign(id, siteId, campaignKey, displayName, purposeDescription, normalizedCurrency,
            suggestedAmounts, minimumAmount, maximumAmount, customAmountAllowed, anonymousAllowed, status,
            activatedOnce, DonationDomainRules.nextVersion(version), createdAt, now);
    }

    public void validateAmount(long amountMinor) {
        if (amountMinor < minimumAmount || amountMinor > maximumAmount
            || (!customAmountAllowed && !suggestedAmounts.contains(amountMinor))) {
            throw new DomainValidationException("donation amount is not allowed by campaign");
        }
    }

    public void requireCheckoutAllowed() {
        if (status != CampaignStatus.ACTIVE) throw new DomainValidationException("campaign must be active for checkout");
    }

    public void validateAnonymousAllowed(boolean anonymous) {
        if (anonymous && !anonymousAllowed) throw new DomainValidationException("campaign requires an identified donor");
    }

    public DonationCampaignSnapshot snapshot() {
        return new DonationCampaignSnapshot(id, campaignKey, version, currency);
    }

    private DonationCampaign transition(CampaignStatus target, boolean activatedOnce, Instant now) {
        DonationDomainRules.transitionTime(now, updatedAt);
        return new DonationCampaign(id, siteId, campaignKey, displayName, purposeDescription, currency,
            suggestedAmounts, minimumAmount, maximumAmount, customAmountAllowed, anonymousAllowed, target,
            activatedOnce, DonationDomainRules.nextVersion(version), createdAt, now);
    }

    private void requireNotArchived() {
        if (status == CampaignStatus.ARCHIVED) throw new DomainValidationException("archived campaign is terminal");
    }

    public DonationCampaignId id() { return id; }
    public SiteId siteId() { return siteId; }
    public DonationCampaignKey campaignKey() { return campaignKey; }
    public String displayName() { return displayName; }
    public String purposeDescription() { return purposeDescription; }
    public String currency() { return currency; }
    public List<Long> suggestedAmounts() { return suggestedAmounts; }
    public long minimumAmount() { return minimumAmount; }
    public long maximumAmount() { return maximumAmount; }
    public boolean customAmountAllowed() { return customAmountAllowed; }
    public boolean anonymousAllowed() { return anonymousAllowed; }
    public CampaignStatus status() { return status; }
    public boolean activatedOnce() { return activatedOnce; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
