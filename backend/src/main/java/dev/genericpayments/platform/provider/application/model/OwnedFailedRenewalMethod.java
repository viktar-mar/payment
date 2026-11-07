package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort.Sample;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.Instant;
import java.util.UUID;

/** Descriptions only: consumers must compare every receipt to its durable original. */
public final class OwnedFailedRenewalMethod {
    private OwnedFailedRenewalMethod() {}
    public enum Phase { METHOD, RECOVERED }
    public static String factKey(ReadClaim q) {
        return "owned-failed-renewal-method:"+q.reservationId()+":"+q.phase()+":"+q.invocation();
    }
    public static String recoveryAuthority(Reservation r) { return OwnedFailedRenewal.recoveryAuthority(r.admission()); }
    public record Reservation(UUID id,OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Failure failure,
            UUID setupOperationId,String requestFingerprint,long initialOperationVersion,CustomerBinding binding,
            Sample billingSample,Instant reservedAt) {
        public Reservation {
            required(id,admission,failure,setupOperationId,binding,reservedAt);isolated(billingSample);
            var i=admission.firstRenewalAdmission().receipt().intent();var owner=i.clock().owner();
            if(!failure.admissionId().equals(admission.id())||initialOperationVersion!=0
                    ||requestFingerprint==null||!requestFingerprint.matches("[A-Za-z0-9_.:/-]{1,255}")
                    ||binding.id()==null||binding.creationOperationId()==null||binding.version()<0
                    ||!owner.account().equals(binding.accountId())||!owner.site().equals(binding.siteId())||!owner.customer().equals(binding.customerId())
                    ||!i.customerBinding().equals(binding.id())||!i.customerCreationOperation().equals(binding.creationOperationId())
                    ||!i.customerReference().equals(binding.providerReference())
                    ||!billingSample.billingAt().equals(Instant.ofEpochSecond(admission.ready().ack().advance().target()))
                    ||reservedAt.isBefore(failure.recordedAt()))throw invalid();
        }
    }
    public record ReadClaim(UUID id,UUID reservationId,Phase phase,UUID invocation,Instant startedAt,
            Instant deadlineAt,Fence fence,Sample billingSample) {
        public ReadClaim {
            required(id,reservationId,phase,invocation,startedAt,deadlineAt,fence);isolated(billingSample);
            if(!deadlineAt.equals(startedAt.plusSeconds(30))||fence.revision()<0||fence.observationSequence()==null
                    ||fence.observationSequence()<0||fence.operationId()==null||fence.operationVersion()==null
                    ||fence.operationVersion()<0||fence.lease()!=null||fence.authority()!=null)throw invalid();
        }
        public void requireAccepted(Instant observedAt,Instant acceptedAt) {
            required(observedAt,acceptedAt);
            if(observedAt.isBefore(startedAt)||acceptedAt.isBefore(observedAt)||!acceptedAt.isBefore(deadlineAt))throw invalid();
        }
    }
    public record Completion(UUID reservationId,UUID claimId,UUID setupOperationId,long setupFinalVersion,
            UUID setupStepId,long setupStepVersion,UUID methodStepId,long methodStepVersion,
            String setupIntent,String method,long revision,long observationSequence,Instant completedAt) {
        public Completion {
            required(reservationId,claimId,setupOperationId,setupStepId,methodStepId,completedAt);
            if(setupFinalVersion<0||setupStepVersion<0||methodStepVersion<0||revision<0||observationSequence<0
                    ||setupStepId.equals(methodStepId)||!matches(setupIntent,"seti_[A-Za-z0-9]{1,250}")
                    ||!matches(method,"pm_[A-Za-z0-9]{1,252}"))throw invalid();
        }
    }
    public record Recovery(UUID reservationId,OwnedFailedRenewal.Recovery association,long observationSequence) {
        public Recovery { required(reservationId,association);if(observationSequence<0)throw invalid(); }
    }
    public record Publication(UUID reservationId,OwnedFailedRenewal.Publication publication,long observationSequence) {
        public Publication { required(reservationId,publication);if(observationSequence<0)throw invalid(); }
    }
    public record MethodReadContext(PaymentAccount account,Subscription subscription,CustomerBinding binding,
            SubscriptionOperation operation,Setup setupProof,ProviderOperation setupStep,ProviderOperation methodStep,Sample billingSample) {
        public MethodReadContext { required(account,subscription,binding,operation,setupProof,setupStep,methodStep);isolated(billingSample); }
    }
    public record RecoveredReadContext(PaymentAccount account,Subscription subscription,CustomerBinding binding,
            SubscriptionOperation operation,Completion completion,Recovery recovery,Sample billingSample) {
        public RecoveredReadContext { required(account,subscription,binding,operation,completion,recovery);isolated(billingSample); }
    }
    public record MethodEvidence(Snapshot snapshot,String setupIntent,String method,Instant acceptedAt) {
        public MethodEvidence {
            required(snapshot,acceptedAt);
            if(!matches(setupIntent,"seti_[A-Za-z0-9]{1,250}")||!matches(method,"pm_[A-Za-z0-9]{1,252}"))throw invalid();
        }
    }
    private static void isolated(Sample s) { if(s==null||!s.isolated())throw invalid(); }
    private static void required(Object... values) { for(var v:values)if(v==null)throw invalid(); }
    private static boolean matches(String s,String pattern) { return s!=null&&s.matches(pattern); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid owned method transition evidence"); }
}
