package dev.genericpayments.platform.donations.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record DonationId(UUID value) {
    public DonationId {
        if (value == null) throw new DomainValidationException("donation id is required");
    }
}
