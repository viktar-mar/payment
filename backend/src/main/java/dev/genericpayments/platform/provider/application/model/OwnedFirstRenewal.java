package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

/** Internal persisted evidence. Records alone confer no authority: every consumer reloads them. */
public final class OwnedFirstRenewal {
    private OwnedFirstRenewal() {}
    public record Admission(UUID id,OwnedClockSubscriptionReceipt receipt,String sourceSha,
            UUID root,UUID attempt,long attemptVersion,UUID bill,long billVersion,UUID payment,long paymentVersion,
            String invoice,String allocation,String paymentIntent,long subscriptionRevision,long policyVersion,
            String fingerprint,Instant admittedAt) {
        public Admission {
            required(id,receipt,root,attempt,bill,payment,admittedAt);
            if(!matches(sourceSha,"[a-f0-9]{40}")||attemptVersion<1||billVersion<0||paymentVersion<0||subscriptionRevision<0
                    ||policyVersion!=receipt.intent().policyVersion()||admittedAt.isBefore(receipt.acknowledgedAt())
                    ||!matches(invoice,"in_[A-Za-z0-9]{1,252}")||!matches(allocation,"inpay_[A-Za-z0-9]{1,249}")
                    ||!matches(paymentIntent,"pi_[A-Za-z0-9]{1,252}")||!hash(fingerprint))throw invalid();
        }
    }
    public enum Step {
        BOUNDARY(1),FINALIZATION(2);
        private final int ordinal;
        Step(int ordinal){this.ordinal=ordinal;}
        public int number(){return ordinal;}
    }
    public record Advance(UUID id,UUID admissionId,Step step,long target,UUID predecessorReady,
            UUID boundary,String operationKey,String fingerprint,Instant preparedAt) {
        public Advance {
            required(id,admissionId,step,preparedAt);
            if(target<=0||!Objects.equals(operationKey,"billing-renewal-advance-v1:"+admissionId+":"+step.number())||!hash(fingerprint)
                    ||step==Step.BOUNDARY&&(predecessorReady!=null||boundary!=null)
                    ||step==Step.FINALIZATION&&(predecessorReady==null||boundary==null))throw invalid();
        }
    }
    public record TestClockAck(String reference,String object,boolean livemode,String name,long frozenTime,
            String status,long created,long deletesAfter,Long advancingTarget) {
        public TestClockAck {clock(reference,object,livemode,name,frozenTime,status,created,deletesAfter,advancingTarget);}
    }
    public record TestClockReady(String reference,String object,boolean livemode,String name,long frozenTime,
            String status,long created,long deletesAfter,Long advancingTarget) {
        public TestClockReady {
            clock(reference,object,livemode,name,frozenTime,status,created,deletesAfter,advancingTarget);
            if(!"ready".equals(status)||advancingTarget!=null&&advancingTarget!=frozenTime)throw invalid();
        }
    }
    public record Ack(Advance advance,ProviderOperation operation,String claimant,Instant deadline,
            TestClockAck observation,Instant acknowledgedAt) {
        public Ack {
            required(advance,operation,deadline,observation,acknowledgedAt);
            if(claimant==null||claimant.isBlank()||!"TEST_CLOCK_ADVANCE".equals(operation.kind().name())||operation.status()!=ProviderOperation.Status.ACKNOWLEDGED
                    ||!operation.resourceId().equals(advance.id())||!operation.operationKey().equals(advance.operationKey())
                    ||!operation.fingerprint().equals(advance.fingerprint())||!Objects.equals(operation.providerReference(),observation.reference())
                    ||!acknowledgedAt.isBefore(deadline)||observation.advancingTarget()!=null&&observation.advancingTarget()!=advance.target())throw invalid();
        }
    }
    public record LaterReady(UUID id,UUID admissionId,Ack ack,TestClockReady observation,Instant observedAt) {
        public LaterReady {
            required(id,admissionId,ack,observation,observedAt);
            var post=ack.observation();
            if(!admissionId.equals(ack.advance().admissionId())||observation.frozenTime()!=ack.advance().target()
                    ||!observation.reference().equals(post.reference())||!observation.name().equals(post.name())
                    ||observation.created()!=post.created()||observation.deletesAfter()!=post.deletesAfter()||observedAt.isBefore(ack.acknowledgedAt()))throw invalid();
        }
    }
    public record Boundary(UUID id,UUID admissionId,LaterReady ready,String item,String renewalInvoice,
            Subscription.Period period,String fingerprint,Instant observedAt) {
        public Boundary {
            required(id,admissionId,ready,period,observedAt);
            if(!admissionId.equals(ready.admissionId())||ready.ack().advance().step()!=Step.BOUNDARY
                    ||period.start().getEpochSecond()!=ready.observation().frozenTime()||!period.start().equals(Instant.ofEpochSecond(ready.observation().frozenTime()))
                    ||!matches(item,"si_[A-Za-z0-9]{1,252}")||!matches(renewalInvoice,"in_[A-Za-z0-9]{1,252}")
                    ||!hash(fingerprint)||observedAt.isBefore(ready.observedAt()))throw invalid();
        }
    }
    public sealed interface Observation permits Pending,Paid {}
    public record Pending(Admission admission,LaterReady ready,Boundary boundary) implements Observation {
        public Pending {observation(admission,ready,boundary);}
    }
    public record Paid(Admission admission,LaterReady ready,Boundary boundary,
            ApplySubscriptionSnapshotUseCase.Fence fence,Snapshot snapshot) implements Observation {
        public Paid {observation(admission,ready,boundary);required(fence,snapshot);if(ready.ack().advance().step()!=Step.FINALIZATION)throw invalid();}
    }
    public sealed interface MappedObservation permits MappedPending,MappedPaid {}
    public record MappedPending(Boundary boundary) implements MappedObservation {public MappedPending {required(boundary);}}
    public record MappedPaid(Boundary boundary,Snapshot snapshot) implements MappedObservation {public MappedPaid {required(boundary,snapshot);}}
    public record Publication(UUID admissionId,LaterReady ready,Boundary boundary,UUID bill,UUID payment,
            long projectedRevision,Instant publishedAt) {
        public Publication {
            required(admissionId,ready,boundary,bill,payment,publishedAt);
            if(!admissionId.equals(ready.admissionId())||!admissionId.equals(boundary.admissionId())||ready.ack().advance().step()!=Step.FINALIZATION
                    ||projectedRevision<0||publishedAt.isBefore(ready.observedAt()))throw invalid();
        }
    }
    /** Versioned explicit-field encoding. Callers pass scalar identity fields, never record.toString(). */
    public static String fingerprint(Object... fields){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
            dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.canonical(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void observation(Admission a,LaterReady r,Boundary b){
        required(a,r,b);
        if(!a.id().equals(r.admissionId())||!a.id().equals(b.admissionId())||!a.receipt().itemReference().equals(b.item())
                ||!b.period().start().equals(Instant.ofEpochSecond(a.receipt().periodEnd())))throw invalid();
    }
    private static void clock(String ref,String object,boolean live,String name,long frozen,String status,long created,long deletes,Long target){
        if(!matches(ref,"clock_[A-Za-z0-9]{1,249}")||!"test_helpers.test_clock".equals(object)||live||name==null||name.isBlank()||name.length()>255||name.codePoints().anyMatch(Character::isISOControl)
                ||frozen<=0||created<=0||deletes<=0||!("ready".equals(status)||"advancing".equals(status))||target!=null&&target<=0)throw invalid();
    }
    private static void required(Object... values){for(var v:values)if(v==null)throw invalid();}
    private static boolean matches(String v,String pattern){return v!=null&&v.matches(pattern);}
    private static boolean hash(String v){return matches(v,"[a-f0-9]{64}");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid owned first renewal evidence");}
}
