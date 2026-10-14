package dev.genericpayments.platform.donations.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

public record DonationCampaignKey(String value) {
    private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{2,62}");

    public DonationCampaignKey {
        DonationDomainRules.required(value, "campaign key");
        value = value.toLowerCase(Locale.ROOT);
        if (!SAFE_KEY.matcher(value).matches()) {
            throw new DomainValidationException("campaign key must match [a-z0-9][a-z0-9-]{2,62}");
        }
    }
}
