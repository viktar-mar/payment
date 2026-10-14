package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.UUID;

public interface CustomerIdempotencyStore {
    enum Status { IN_PROGRESS, COMPLETED }
    record Record(UUID id,SiteId siteId,String scope,String keyDigest,String fingerprint,Status status,
        UUID resourceId,Integer responseStatus,Instant createdAt,Instant updatedAt,Instant expiresAt,long version) {}
    record Claim(boolean owner,Record record) {}
    Claim claim(Record candidate);
    Record complete(UUID id,SiteId siteId,String scope,String keyDigest,long expectedVersion,UUID resourceId,int responseStatus,Instant now);
}
