package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;

public interface DistributedCustomerRateLimitPort {
    void acquire(SiteId site,KeyedDigestPort.DigestValue siteDigest,KeyedDigestPort.DigestValue emailDigest,
        KeyedDigestPort.DigestValue sourceAddressDigest,Instant now);
    int cleanupExpired(Instant now,int maximumRows);
}
