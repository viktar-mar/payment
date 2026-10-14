package dev.genericpayments.platform.subscriptions.domain;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.*;
public record SubscriptionOperation(UUID id,SiteId siteId,CustomerId customerId,UUID subscriptionId,
    Kind kind,Status status,String authorityScope,String keyDigest,String fingerprint,String providerOperationKey,
    long expectedRevision,long policyVersion,String providerReference,String safeFailureCode,Instant createdAt,Instant updatedAt,long version) {
    public enum Kind { CREATE, CHANGE, CLEAR_CHANGE, CANCEL, WITHDRAW_CANCEL, SETUP, RECOVER, RESUME, PORTAL, RECONCILE }
    public enum Status { RESERVED, PROCESSING, REQUIRES_ACTION, UNKNOWN, SUCCEEDED, FAILED }
    public SubscriptionOperation {
        Objects.requireNonNull(id);Objects.requireNonNull(siteId);Objects.requireNonNull(customerId);Objects.requireNonNull(kind);Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);
        if(kind!=Kind.CREATE)Objects.requireNonNull(subscriptionId);
        if(expectedRevision<0||policyVersion<0||version<0||updatedAt.isBefore(createdAt))throw new DomainValidationException("Invalid operation counters or times");
        for(String value:List.of(Objects.requireNonNull(authorityScope),Objects.requireNonNull(keyDigest),Objects.requireNonNull(fingerprint),Objects.requireNonNull(providerOperationKey)))safe(value);
        safe(providerReference);safe(safeFailureCode);
    }
    private static void safe(String value){if(value!=null&&(value.isBlank()||value.length()>255||!value.matches("[A-Za-z0-9_.:/-]+")))throw new DomainValidationException("Invalid safe operation reference");}
}
