package dev.genericpayments.platform.donations.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record DonationCampaignId(UUID value) {
    public DonationCampaignId {
        if (value == null) throw new DomainValidationException("donation campaign id is required");
    }
}
