package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import java.time.Instant;
import java.util.UUID;

/** Internal persisted evidence. Records alone confer no authority: every consumer reloads them. */
public final class OwnedFirstRenewal {
    private OwnedFirstRenewal() {}
    public record Admission(UUID id,OwnedClockSubscriptionReceipt receipt,String sourceSha,
            UUID root,UUID attempt,UUID bill,long billVersion,UUID payment,long paymentVersion,
            String invoice,String allocation,String paymentIntent,long subscriptionRevision,long policyVersion,
            String fingerprint,Instant admittedAt) {}
    public enum Step {
        BOUNDARY(1),FINALIZATION(2);
        private final int ordinal;
        Step(int ordinal){this.ordinal=ordinal;}
        public int number(){return ordinal;}
    }
    public record Advance(UUID id,UUID admissionId,Step step,long target,UUID predecessorReady,
            UUID boundary,String operationKey,String fingerprint,Instant preparedAt) {}
    public record TestClockAck(String reference,String object,boolean livemode,String name,long frozenTime,
            String status,long created,long deletesAfter,Long advancingTarget) {}
    public record TestClockReady(String reference,String object,boolean livemode,String name,long frozenTime,
            String status,long created,long deletesAfter,Long advancingTarget) {}
    public record Ack(Advance advance,ProviderOperation operation,String claimant,Instant deadline,
            TestClockAck observation,Instant acknowledgedAt) {}
    public record LaterReady(UUID id,UUID admissionId,Ack ack,TestClockReady observation,Instant observedAt) {}
    public record Boundary(UUID id,UUID admissionId,LaterReady ready,String item,String renewalInvoice,
            Subscription.Period period,String fingerprint,Instant observedAt) {}
    public sealed interface Observation permits Pending,Paid {}
    public record Pending(Admission admission,LaterReady ready,Boundary boundary) implements Observation {}
    public record Paid(Admission admission,LaterReady ready,Boundary boundary,
            ApplySubscriptionSnapshotUseCase.Fence fence,Snapshot snapshot) implements Observation {}
    public sealed interface MappedObservation permits MappedPending,MappedPaid {}
    public record MappedPending(Boundary boundary) implements MappedObservation {}
    public record MappedPaid(Boundary boundary,Snapshot snapshot) implements MappedObservation {}
    public record Publication(UUID admissionId,LaterReady ready,Boundary boundary,UUID bill,UUID payment,
            long projectedRevision,Instant publishedAt) {}
}
