package dev.genericpayments.platform.payments.application.model;

import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Operation;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public record IdempotencyRecord(UUID id, String scope, String key, String fingerprint, Status status,
    Resource resource, Integer responseStatus, SecretState secretState, EncryptedValue secret,
    Instant secretExpiresAt, Instant expiresAt, Instant createdAt, Instant updatedAt, long version) {

    public enum Status { IN_PROGRESS, COMPLETED }
    public enum SecretState { NONE, LIVE, EXPIRED }
    public enum ResourceType { PAYMENT, CHECKOUT, HANDOFF, REFUND, OUTBOX_EVENT, OUTBOX_RETRY_REQUEST, DONATION_CAMPAIGN, ATTEMPT, PROVIDER_OPERATION }
    public enum Actor { PUBLIC, PARTNER, ADMIN }
    public record Resource(ResourceType type, UUID id) {
        public Resource { Objects.requireNonNull(type); Objects.requireNonNull(id); }
    }
    /** Scope is built from trusted authentication/routing facts, never a raw request scope. */
    public record Scope(Actor actor, UUID siteId, UUID actorId, Operation operation, UUID subjectId) {
        public Scope {
            Objects.requireNonNull(actor); Objects.requireNonNull(siteId); Objects.requireNonNull(operation);
            if ((actor == Actor.ADMIN) != (actorId != null)) throw new IllegalArgumentException("Invalid scope actor");
            if ((operation == Operation.CHECKOUT_CONFIRM || operation == Operation.CHECKOUT_CLIENT_SESSION) && subjectId == null)
                throw new IllegalArgumentException("Checkout confirmation scope requires its checkout identity");
        }
        public static Scope partner(UUID site, Operation operation) { return new Scope(Actor.PARTNER,site,null,operation,null); }
        public static Scope partner(UUID site, Operation operation, UUID subject) { return new Scope(Actor.PARTNER,site,null,operation,subject); }
        public static Scope admin(UUID site, UUID actor, Operation operation) { return new Scope(Actor.ADMIN,site,actor,operation,null); }
        public static Scope publicOperation(UUID site, Operation operation, UUID subject) { return new Scope(Actor.PUBLIC,site,null,operation,subject); }
        public String value() {
            return actor.name().toLowerCase(Locale.ROOT)+":"+siteId+":"+(actorId == null ? "" : actorId+":")
                +operation.scope()+(subjectId == null ? "" : ":"+subjectId);
        }
    }
    public record Ownership(UUID id, String scope, String key, String fingerprint, long version) {
        public Ownership {
            Objects.requireNonNull(id); validateText(scope,255); validateText(key,255); validateFingerprint(fingerprint);
            if (version < 0) throw new IllegalArgumentException("Invalid ownership version");
        }
        @Override public String toString() { return "Ownership[id="+id+", version="+version+"]"; }
    }
    public record Completion(Resource resource, int responseStatus, EncryptedValue secret, Instant secretExpiresAt) {
        public Completion {
            Objects.requireNonNull(resource);
            if (responseStatus < 100 || responseStatus > 599 || (secret == null) != (secretExpiresAt == null))
                throw new IllegalArgumentException("Invalid completion");
        }
    }
    public IdempotencyRecord {
        Objects.requireNonNull(id); validateText(scope,255); validateText(key,255); validateFingerprint(fingerprint);
        Objects.requireNonNull(status); Objects.requireNonNull(secretState);
        Objects.requireNonNull(expiresAt); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (version < 0 || updatedAt.isBefore(createdAt) || !expiresAt.isAfter(createdAt)) throw invalid();
        if (status == Status.IN_PROGRESS && (resource != null || responseStatus != null || secretState != SecretState.NONE)) throw invalid();
        if (status == Status.COMPLETED && (resource == null || responseStatus == null || responseStatus < 100 || responseStatus > 599)) throw invalid();
        if (secretState == SecretState.NONE && (secret != null || secretExpiresAt != null)
            || secretState == SecretState.LIVE && (secret == null || secretExpiresAt == null)
            || secretState == SecretState.EXPIRED && (secret != null || secretExpiresAt == null)) throw invalid();
        if (secretExpiresAt != null && (!secretExpiresAt.isAfter(createdAt) || secretExpiresAt.isAfter(expiresAt))) throw invalid();
    }
    public static IdempotencyRecord begin(UUID id, Scope scope, String key, String fingerprint, Instant now) {
        return new IdempotencyRecord(id,scope.value(),key,fingerprint,Status.IN_PROGRESS,null,null,SecretState.NONE,null,null,
            now.plus(Duration.ofDays(30)),now,now,0);
    }
    public IdempotencyRecord complete(Ownership owner, Completion completion, Instant now) {
        if (status != Status.IN_PROGRESS || !id.equals(owner.id()) || !scope.equals(owner.scope()) || !key.equals(owner.key())
            || !fingerprint.equals(owner.fingerprint()) || version != owner.version())
            throw new IllegalStateException("Idempotency ownership is stale or mismatched");
        Instant retention = now.plus(Duration.ofDays(30));
        if (completion.secretExpiresAt() != null && (!completion.secretExpiresAt().isAfter(now) || completion.secretExpiresAt().isAfter(retention)))
            throw new IllegalArgumentException("Invalid response secret expiry");
        return new IdempotencyRecord(id,scope,key,fingerprint,Status.COMPLETED,completion.resource(),completion.responseStatus(),
            completion.secret() == null ? SecretState.NONE : SecretState.LIVE,completion.secret(),completion.secretExpiresAt(),retention,createdAt,now,version+1);
    }
    public static void validateText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    }
    public static void validateFingerprint(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid idempotency record"); }
    @Override public String toString() { return "IdempotencyRecord[id="+id+", status="+status+", version="+version+"]"; }
}
