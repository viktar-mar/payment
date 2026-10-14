package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.port.out.IdempotencyPort;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.ReplayState;
import java.time.*;
import java.util.*;

public final class IdempotencyService {
    private final IdempotencyPort records;
    private final EncryptedSecretPort encryption;
    private final Stage2TelemetryPort telemetry;
    private final Clock clock;
    private final CanonicalFingerprint fingerprints;
    public IdempotencyService(IdempotencyPort records, EncryptedSecretPort encryption, Stage2TelemetryPort telemetry, DigestPort digest, Clock clock) {
        this.records=Objects.requireNonNull(records); this.encryption=Objects.requireNonNull(encryption);
        this.telemetry=Objects.requireNonNull(telemetry); this.clock=Objects.requireNonNull(clock);
        this.fingerprints=new CanonicalFingerprint(digest);
    }
    public sealed interface Outcome permits Owner, InProgress, Completed, ExpiredSecret, Conflict {}
    public record Owner(UUID id, Scope scope, String key, String fingerprint, long version) implements Outcome {
        public Owner { new Ownership(id,scope.value(),key,fingerprint,version); }
        Ownership token() { return new Ownership(id,scope.value(),key,fingerprint,version); }
        @Override public String toString() { return "Owner[id="+id+", version="+version+"]"; }
    }
    /** The durable operation ID is a safe reference even before a business resource is linked. */
    public record InProgress(UUID operationId) implements Outcome {}
    public record Completed(Resource resource, int responseStatus, Optional<String> secret) implements Outcome {
        @Override public String toString() { return "Completed[resource="+resource+", responseStatus="+responseStatus+", secret=redacted]"; }
    }
    public record ExpiredSecret(Resource resource, int responseStatus) implements Outcome {}
    public record Conflict() implements Outcome {}
    public record SecretFragment(String value, Instant expiresAt) {
        public SecretFragment {
            if (value == null || value.isEmpty() || expiresAt == null) throw new IllegalArgumentException("Secret and expiry are required");
        }
        @Override public String toString() { return "SecretFragment[redacted]"; }
    }
    public String fingerprint(Map<String, ?> businessFields) { return fingerprints.hash(businessFields); }
    public Outcome begin(Scope scope, String key, String fingerprint) {
        return beginCompatibleReplay(scope,key,fingerprint,Set.of());
    }
    Outcome beginCompatibleReplay(Scope scope,String key,String fingerprint,Set<String> compatibleReplayFingerprints) {
        Instant now=clock.instant();
        var candidate=IdempotencyRecord.begin(UUID.randomUUID(),scope,key,fingerprint,now);
        var claim=records.claim(candidate,now);
        var record=claim.record();
        if (claim.owner()) return new Owner(record.id(),scope,key,fingerprint,record.version());
        if (!record.fingerprint().equals(fingerprint) && !compatibleReplayFingerprints.contains(record.fingerprint())) {
            telemetry.idempotencyConflict(scope.operation()); return new Conflict();
        }
        if (record.status() == Status.IN_PROGRESS) {
            telemetry.idempotencyReplay(scope.operation(),ReplayState.IN_PROGRESS); return new InProgress(record.id());
        }
        // Claim can wait on a database lock. Re-read time before releasing any plaintext.
        now=clock.instant();
        if (record.secretState() == SecretState.EXPIRED || record.secretExpiresAt() != null && !now.isBefore(record.secretExpiresAt())) {
            if (record.secretState() == SecretState.LIVE) records.expireSecret(record.id(),now);
            telemetry.idempotencyReplay(scope.operation(),ReplayState.EXPIRED_SECRET);
            return new ExpiredSecret(record.resource(),record.responseStatus());
        }
        Optional<String> secret=record.secretState() == SecretState.LIVE ? Optional.of(encryption.decrypt(record.secret())) : Optional.empty();
        telemetry.idempotencyReplay(scope.operation(),ReplayState.COMPLETED);
        return new Completed(record.resource(),record.responseStatus(),secret);
    }
    public void complete(Owner owner, Resource resource, int responseStatus, SecretFragment secret) {
        Instant now=clock.instant();
        if (secret != null && (!secret.expiresAt().isAfter(now) || secret.expiresAt().isAfter(now.plus(Duration.ofDays(30)))))
            throw new IllegalArgumentException("Invalid response secret expiry");
        if (resource == null || responseStatus < 100 || responseStatus > 599) throw new IllegalArgumentException("Invalid completion");
        records.complete(owner.token(),new Completion(resource,responseStatus,
            secret == null ? null : encryption.encrypt(secret.value()),secret == null ? null : secret.expiresAt()),now);
    }
}
