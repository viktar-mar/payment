package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

/** The aggregate intentionally contains only a keyed digest; raw bearer material never enters it. */
public final class CustomerSession {
    public enum RevocationReason { LOGOUT, ANONYMIZED, ADMIN, EXPIRED }

    private final CustomerSessionId id;
    private final SiteId siteId;
    private final CustomerId customerId;
    private final String origin;
    private final String tokenDigest;
    private final String tokenDigestKeyVersion;
    private final Set<CustomerSessionScope> scopes;
    private final Instant issuedAt;
    private final Instant lastUsedAt;
    private final Instant expiresAt;
    private final Instant revokedAt;
    private final RevocationReason revokedReason;
    private final long version;

    private CustomerSession(CustomerSessionId id,SiteId siteId,CustomerId customerId,String origin,
            String tokenDigest,String tokenDigestKeyVersion,Set<CustomerSessionScope> scopes,
            Instant issuedAt,Instant lastUsedAt,Instant expiresAt,Instant revokedAt,
            RevocationReason revokedReason,long version) {
        this.id=Objects.requireNonNull(id,"customer session id");
        this.siteId=Objects.requireNonNull(siteId,"site id");
        this.customerId=Objects.requireNonNull(customerId,"customer id");
        if(origin==null || origin.isBlank() || origin.length()>2048) throw new DomainValidationException("invalid session origin");
        if(tokenDigest==null || tokenDigest.length()>512 || !tokenDigest.matches("[A-Za-z0-9_-]+"))
            throw new DomainValidationException("invalid session token digest");
        if(tokenDigestKeyVersion==null || !tokenDigestKeyVersion.matches("v[1-9][0-9]{0,8}"))
            throw new DomainValidationException("invalid session token digest version");
        if(scopes==null || scopes.isEmpty()) throw new DomainValidationException("customer session scopes are required");
        this.origin=origin; this.tokenDigest=tokenDigest; this.tokenDigestKeyVersion=tokenDigestKeyVersion;
        this.scopes=Collections.unmodifiableSet(EnumSet.copyOf(scopes));
        this.issuedAt=Objects.requireNonNull(issuedAt,"issued at");
        this.lastUsedAt=Objects.requireNonNull(lastUsedAt,"last used at");
        this.expiresAt=Objects.requireNonNull(expiresAt,"expires at");
        if(!expiresAt.isAfter(issuedAt) || lastUsedAt.isBefore(issuedAt) || lastUsedAt.isAfter(expiresAt))
            throw new DomainValidationException("invalid customer session times");
        if((revokedAt==null)!=(revokedReason==null) || revokedAt!=null&&revokedAt.isBefore(issuedAt))
            throw new DomainValidationException("invalid customer session revocation");
        if(version<0) throw new DomainValidationException("customer session version must not be negative");
        this.revokedAt=revokedAt; this.revokedReason=revokedReason; this.version=version;
    }

    public static CustomerSession create(CustomerSessionId id,SiteId siteId,CustomerId customerId,String origin,
            String tokenDigest,String tokenDigestKeyVersion,Set<CustomerSessionScope> scopes,Instant now,Instant expiresAt) {
        return new CustomerSession(id,siteId,customerId,origin,tokenDigest,tokenDigestKeyVersion,scopes,
            now,now,expiresAt,null,null,0);
    }

    public static CustomerSession reconstitute(CustomerSessionId id,SiteId siteId,CustomerId customerId,String origin,
            String tokenDigest,String tokenDigestKeyVersion,Set<CustomerSessionScope> scopes,Instant issuedAt,
            Instant lastUsedAt,Instant expiresAt,Instant revokedAt,RevocationReason revokedReason,long version) {
        return new CustomerSession(id,siteId,customerId,origin,tokenDigest,tokenDigestKeyVersion,scopes,
            issuedAt,lastUsedAt,expiresAt,revokedAt,revokedReason,version);
    }

    public boolean activeAt(Instant now) { return revokedAt==null && now.isBefore(expiresAt); }
    public CustomerSession use(Instant now) {
        if(!activeAt(now) || now.isBefore(lastUsedAt)) throw new DomainValidationException("customer session is not active");
        return new CustomerSession(id,siteId,customerId,origin,tokenDigest,tokenDigestKeyVersion,scopes,
            issuedAt,now,expiresAt,null,null,version);
    }
    public CustomerSession revoke(Instant now,RevocationReason reason) {
        Objects.requireNonNull(reason,"revocation reason");
        if(revokedAt!=null) return this;
        if(now.isBefore(issuedAt)) throw new DomainValidationException("invalid revocation time");
        return new CustomerSession(id,siteId,customerId,origin,tokenDigest,tokenDigestKeyVersion,scopes,
            issuedAt,lastUsedAt,expiresAt,now,reason,version);
    }

    public CustomerSessionId id(){return id;} public SiteId siteId(){return siteId;}
    public CustomerId customerId(){return customerId;} public String origin(){return origin;}
    public String tokenDigest(){return tokenDigest;} public String tokenDigestKeyVersion(){return tokenDigestKeyVersion;}
    public Set<CustomerSessionScope> scopes(){return scopes;} public Instant issuedAt(){return issuedAt;}
    public Instant lastUsedAt(){return lastUsedAt;} public Instant expiresAt(){return expiresAt;}
    public Optional<Instant> revokedAt(){return Optional.ofNullable(revokedAt);}
    public Optional<RevocationReason> revokedReason(){return Optional.ofNullable(revokedReason);}
    public long version(){return version;}
    @Override public String toString(){return "CustomerSession[id="+id+", siteId="+siteId+", customerId="+customerId+"]";}
}
