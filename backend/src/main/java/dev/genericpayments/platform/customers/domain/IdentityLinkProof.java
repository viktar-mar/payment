package dev.genericpayments.platform.customers.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public record IdentityLinkProof(UUID id,SiteId siteId,String origin,CustomerAuthenticationMode mode,
        String issuerKey,String subjectDigest,List<String> subjectDigests,String tokenDigest,Instant verifiedAt,Instant createdAt,
        Instant expiresAt,Instant consumedAt,long version) {
    public IdentityLinkProof {
        Objects.requireNonNull(id,"proof id"); Objects.requireNonNull(siteId,"site id"); Objects.requireNonNull(mode,"mode");
        Objects.requireNonNull(verifiedAt,"verification time"); Objects.requireNonNull(createdAt,"creation time"); Objects.requireNonNull(expiresAt,"expiry time");
        subjectDigests=List.copyOf(Objects.requireNonNull(subjectDigests,"subject digests"));
        if(origin==null || origin.isBlank() || origin.length()>2048 || issuerKey==null || !issuerKey.matches("[a-z0-9][a-z0-9-]{2,62}")
            || !digest(subjectDigest) || subjectDigests.isEmpty() || subjectDigests.size()>20
            || !subjectDigest.equals(subjectDigests.getFirst()) || subjectDigests.stream().anyMatch(value -> !digest(value))
            || new HashSet<>(subjectDigests).size()!=subjectDigests.size() || !digest(tokenDigest)
            || verifiedAt.isAfter(createdAt) || !expiresAt.isAfter(createdAt)
            || consumedAt!=null&&consumedAt.isBefore(createdAt) || version<0) throw new DomainValidationException("invalid identity link proof");
    }
    private static boolean digest(String value) { return value!=null&&value.matches("v[1-9][0-9]{0,8}:[A-Za-z0-9_-]+"); }
    public IdentityLinkProof consume(Instant now) {
        Objects.requireNonNull(now,"consumption time");
        if(consumedAt!=null || now.isBefore(createdAt)) throw new DomainValidationException("invalid identity link proof consumption");
        return new IdentityLinkProof(id,siteId,origin,mode,issuerKey,subjectDigest,subjectDigests,tokenDigest,
            verifiedAt,createdAt,expiresAt,now,version+1);
    }
    @Override public String toString() { return "IdentityLinkProof[id="+id+", siteId="+siteId+", consumed="+(consumedAt!=null)+"]"; }
}
