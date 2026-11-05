package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence descriptions. Only a persisted, reauthenticated row grants authority. */
public final class OwnedFailedRenewal {
    private OwnedFailedRenewal() {}
    public enum Phase { FAILED, RECOVERED }
    public enum Reason { UNCERTAIN, AUTHORITY_DRIFT }
    public static String factKey(ReadClaim claim) {
        return "owned-failed-renewal:" + claim.admissionId() + ":" + claim.phase() + ":" + claim.invocation();
    }
    public static String recoveryAuthority(Admission admission) { return "owned-failed-renewal:" + admission.id(); }
    public record Admission(UUID id, OwnedFirstRenewal.Admission firstRenewalAdmission,
            OwnedFirstRenewal.LaterReady ready, OwnedFirstRenewal.Boundary boundary,
            String sourceSha, Instant admittedAt) {
        public Admission {
            required(id, firstRenewalAdmission, ready, boundary, admittedAt);
            if (!Objects.equals(sourceSha, firstRenewalAdmission.sourceSha())
                    || !sourceSha.matches("[a-f0-9]{40}")
                    || !firstRenewalAdmission.id().equals(ready.admissionId())
                    || !firstRenewalAdmission.id().equals(boundary.admissionId())
                    || ready.ack().advance().step() != OwnedFirstRenewal.Step.FINALIZATION
                    || !Objects.equals(ready.ack().advance().boundary(), boundary.id())
                    || !Objects.equals(ready.ack().advance().predecessorReady(), boundary.ready().id())
                    || !boundary.item().equals(firstRenewalAdmission.receipt().itemReference())
                    || boundary.renewalInvoice().equals(firstRenewalAdmission.invoice())
                    || !boundary.period().start().equals(Instant.ofEpochSecond(firstRenewalAdmission.receipt().periodEnd()))
                    || ready.ack().advance().target()!=Math.addExact(firstRenewalAdmission.receipt().periodEnd(),3600)
                    || !Instant.ofEpochSecond(ready.ack().advance().target()).isBefore(boundary.period().end())
                    || admittedAt.isBefore(ready.observedAt())) throw invalid();
        }
    }
    public record ReadClaim(UUID id, UUID admissionId, Phase phase, UUID invocation,
            Instant startedAt, Instant deadlineAt, Fence fence) {
        public ReadClaim {
            required(id, admissionId, phase, invocation, startedAt, deadlineAt, fence);
            if (!deadlineAt.equals(startedAt.plusSeconds(30)) || fence.revision() < 0
                    || fence.observationSequence() == null || fence.observationSequence() < 0
                    || fence.lease() != null || fence.authority() != null
                    || (phase == Phase.FAILED && (fence.operationId() != null || fence.operationVersion() != null))
                    || (phase == Phase.RECOVERED && (fence.operationId() == null || fence.operationVersion() == null || fence.operationVersion() < 0))) throw invalid();
        }
        public void requireAccepted(Instant observedAt, Instant acceptedAt) {
            required(observedAt, acceptedAt);
            if (observedAt.isBefore(startedAt) || acceptedAt.isBefore(observedAt)
                    || !acceptedAt.isBefore(deadlineAt)) throw invalid();
        }
    }
    public record FailedEvidence(Snapshot snapshot, String allocation, String paymentIntent, Instant acceptedAt) {
        public FailedEvidence {
            required(snapshot, acceptedAt);
            if (!matches(allocation,"inpay_[A-Za-z0-9]{1,249}") || !matches(paymentIntent,"pi_[A-Za-z0-9]{1,252}")) throw invalid();
        }
    }
    public record RecoveredEvidence(Snapshot snapshot, Instant acceptedAt) {
        public RecoveredEvidence { required(snapshot, acceptedAt); }
    }
    public record Failure(UUID admissionId, UUID claimId, UUID billId, long billVersion, String invoice, String allocation,
            String paymentIntent, Subscription.Period period, Instant graceDeadline, long revision,
            long observationSequence, Instant recordedAt) {
        public Failure {
            required(admissionId,claimId,billId,period,graceDeadline,recordedAt);
            if (!matches(invoice,"in_[A-Za-z0-9]{1,252}") || !matches(allocation,"inpay_[A-Za-z0-9]{1,249}")
                    || !matches(paymentIntent,"pi_[A-Za-z0-9]{1,252}") || billVersion < 0 || revision < 0 || observationSequence < 0) throw invalid();
        }
    }
    public record Recovery(UUID admissionId, UUID operationId, UUID billId, String allocation,
            String paymentIntent, long expectedRevision, long operationVersion, Instant associatedAt) {
        public Recovery {
            required(admissionId,operationId,billId,associatedAt);
            if (!matches(allocation,"inpay_[A-Za-z0-9]{1,249}") || !matches(paymentIntent,"pi_[A-Za-z0-9]{1,252}")
                    || expectedRevision < 0 || operationVersion < 0) throw invalid();
        }
    }
    public record Publication(UUID admissionId, UUID claimId, UUID operationId, UUID billId,
            UUID paymentId, long revision, Instant publishedAt) {
        public Publication {
            required(admissionId,claimId,operationId,billId,paymentId,publishedAt);
            if (revision < 0) throw invalid();
        }
    }
    public record Stop(UUID admissionId, Reason reason, Instant stoppedAt) {
        public Stop { required(admissionId,reason,stoppedAt); }
    }
    private static void required(Object... values) { for (var value : values) if (value == null) throw invalid(); }
    private static boolean matches(String value,String pattern) { return value != null && value.matches(pattern); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid owned failed renewal evidence"); }
}
