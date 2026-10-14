package dev.genericpayments.platform.partners.domain;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.util.*;
public record SiteApiKey(SiteApiKeyId id, SiteId siteId, String prefix, String keyHash, Set<PartnerScope> scopes,
    Status status, Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {
    public enum Status { ACTIVE, REVOKED }
    public SiteApiKey {
        Objects.requireNonNull(id); Objects.requireNonNull(siteId); Objects.requireNonNull(createdAt); Objects.requireNonNull(status);
        scopes = Set.copyOf(scopes);
        if (prefix == null || !prefix.matches("[A-Za-z0-9_-]{12}") || keyHash == null || !keyHash.startsWith("$argon2id$")
            || scopes.isEmpty() || expiresAt != null && !expiresAt.isAfter(createdAt)
            || lastUsedAt != null && lastUsedAt.isBefore(createdAt) || (status == Status.REVOKED) != (revokedAt != null)
            || revokedAt != null && revokedAt.isBefore(createdAt)) throw new IllegalArgumentException("Invalid Site API key");
    }
    public boolean active(Instant now) { return status == Status.ACTIVE && (expiresAt == null || now.isBefore(expiresAt)); }
    public SiteApiKey overlap(Instant now) {
        var cap = now.plus(Duration.ofHours(24));
        return new SiteApiKey(id,siteId,prefix,keyHash,scopes,status,createdAt,
            expiresAt == null || cap.isBefore(expiresAt) ? cap : expiresAt,lastUsedAt,revokedAt);
    }
    public SiteApiKey revoke(Instant now) { return new SiteApiKey(id,siteId,prefix,keyHash,scopes,Status.REVOKED,createdAt,expiresAt,lastUsedAt,now); }
    @Override public String toString() { return "SiteApiKey[id=" + id + ", siteId=" + siteId + ", status=" + status + "]"; }
}
