package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public record OidcAuthenticationAttempt(UUID id,SiteId siteId,String providerKey,String origin,
        AuthenticationIntent intent,String nonceDigest,long providerConfigVersion,Instant createdAt,
        Instant expiresAt,Instant consumedAt,long version) {
    public OidcAuthenticationAttempt {
        Objects.requireNonNull(id,"attempt id"); Objects.requireNonNull(siteId,"site id");
        Objects.requireNonNull(intent,"intent"); Objects.requireNonNull(createdAt,"creation time");
        Objects.requireNonNull(expiresAt,"expiry time");
        if(providerKey==null || !providerKey.matches("[a-z0-9][a-z0-9-]{2,62}")) invalid();
        if(origin==null || origin.isBlank() || origin.length()>2048) invalid();
        if(nonceDigest==null || !nonceDigest.matches("v[1-9][0-9]{0,8}:[A-Za-z0-9_-]+")) invalid();
        if(providerConfigVersion<0 || version<0 || !expiresAt.isAfter(createdAt)
            || consumedAt!=null&&consumedAt.isBefore(createdAt)) invalid();
    }
    public boolean activeAt(Instant now) { return consumedAt==null && now.isBefore(expiresAt); }
    public OidcAuthenticationAttempt consume(Instant now) {
        if(!activeAt(now)) invalid();
        return new OidcAuthenticationAttempt(id,siteId,providerKey,origin,intent,nonceDigest,providerConfigVersion,createdAt,expiresAt,now,version);
    }
    private static void invalid() { throw new DomainValidationException("invalid OIDC authentication attempt"); }
    @Override public String toString() { return "OidcAuthenticationAttempt[id="+id+", siteId="+siteId+", consumed="+(consumedAt!=null)+"]"; }
}
