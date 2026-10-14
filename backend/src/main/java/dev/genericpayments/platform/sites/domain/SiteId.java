package dev.genericpayments.platform.sites.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record SiteId(UUID value) {
    public SiteId {
        if (value == null) {
            throw new DomainValidationException("site id is required");
        }
    }
}
