package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public record ConsumedSiteTokenJti(UUID id,SiteId siteId,String issuerKey,String jtiDigest,
        Instant consumedAt,Instant expiresAt,long version) {
    public ConsumedSiteTokenJti {
        Objects.requireNonNull(id,"jti id"); Objects.requireNonNull(siteId,"site id");
        Objects.requireNonNull(consumedAt,"consumption time"); Objects.requireNonNull(expiresAt,"expiry time");
        if(issuerKey==null || !issuerKey.matches("[a-z0-9][a-z0-9-]{2,62}")
            || jtiDigest==null || !jtiDigest.matches("v[1-9][0-9]{0,8}:[A-Za-z0-9_-]+")
            || !expiresAt.isAfter(consumedAt) || version<0) throw new DomainValidationException("invalid consumed token identifier");
    }
    @Override public String toString() { return "ConsumedSiteTokenJti[id="+id+", siteId="+siteId+"]"; }
}
