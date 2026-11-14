package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.PreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockAck;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockReady;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Status;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Closed descriptions of the two edges after the original grace-last-second receipt.
 * Every caller must still authenticate the committed descriptions against current authority. */
public final class OwnedFailedGraceBoundary {
    private static final String DOMAIN="billing-failed-grace-boundary-v1";
    private OwnedFailedGraceBoundary(){}

    public enum Phase {
        AT(2,Kind.OWNED_GRACE_AT_CLOCK_ADVANCE,"at"), AFTER(3,Kind.OWNED_GRACE_AFTER_CLOCK_ADVANCE,"after");
        private final int number;
        private final Kind kind;
        private final String suffix;
        Phase(int number,Kind kind,String suffix){this.number=number;this.kind=kind;this.suffix=suffix;}
        public int number(){return number;}
        public Kind kind(){return kind;}
        public String operationKey(UUID admission){return DOMAIN+":"+Objects.requireNonNull(admission)+":"+suffix;}
    }

    public record ReadyOrigin(UUID readyId,UUID operationId,UUID invocation,String operationFingerprint,
            TestClockReady observation,Instant observedAt){
        public ReadyOrigin {
            required(readyId,operationId,invocation,observation,observedAt);
            if(operationFingerprint==null||!operationFingerprint.matches("[a-f0-9]{64}")||!micro(observedAt))throw denied();
        }
        @Override public String toString(){return "ReadyOrigin[redacted]";}
    }

    public record BoundaryContext(PreparationContext original,Phase phase,UUID originalGraceReadyId,
            ReadyOrigin predecessor,long target,String operationKey,String fingerprint){
        public BoundaryContext {
            required(original,phase,originalGraceReadyId,predecessor);
            var p=original.preparation();var saved=original.admission().ready().observation();var previous=predecessor.observation();
            if((phase==Phase.AT)!=originalGraceReadyId.equals(predecessor.readyId())
                    ||phase==Phase.AT&&!p.fingerprint().equals(predecessor.operationFingerprint())
                    ||predecessor.observedAt().isBefore(p.preparedAt())
                    ||!saved.reference().equals(previous.reference())||!saved.object().equals(previous.object())||saved.livemode()!=previous.livemode()
                    ||!saved.name().equals(previous.name())||saved.created()!=previous.created()||saved.deletesAfter()!=previous.deletesAfter()
                    ||target!=Math.addExact(previous.frozenTime(),1)||target!=OwnedFailedGraceBoundary.target(original,phase)
                    ||!phase.operationKey(original.admission().id()).equals(operationKey)
                    ||!OwnedFailedGraceBoundary.fingerprint(original.admission().id(),p.id(),p.fingerprint(),originalGraceReadyId,phase,
                        predecessor,p.graceDeadline(),target,operationKey).equals(fingerprint))throw denied();
        }
        @Override public String toString(){return "BoundaryContext[redacted]";}
    }

    public static BoundaryContext context(PreparationContext original,Phase phase,UUID originalGraceReadyId,ReadyOrigin predecessor){
        required(original,phase,originalGraceReadyId,predecessor);
        var p=original.preparation();var admission=original.admission().id();long target=target(original,phase);var key=phase.operationKey(admission);
        return new BoundaryContext(original,phase,originalGraceReadyId,predecessor,target,key,
            fingerprint(admission,p.id(),p.fingerprint(),originalGraceReadyId,phase,predecessor,p.graceDeadline(),target,key));
    }

    private static long target(PreparationContext original,Phase phase){
        // PreparationContext has already checked the exact original triplet, whole seconds and term ceiling.
        long g=original.failure().graceDeadline().getEpochSecond();
        return phase==Phase.AT?g:Math.addExact(g,1);
    }

    public static String fingerprint(UUID admission,UUID preparation,String preparationFingerprint,UUID originalGraceReadyId,
            Phase phase,ReadyOrigin predecessor,Instant graceDeadline,long target,String key){
        var c=predecessor.observation();
        return OwnedFirstRenewal.fingerprint(DOMAIN,admission,preparation,preparationFingerprint,originalGraceReadyId,
            phase.number(),phase.kind().name(),predecessor.readyId(),predecessor.operationId(),predecessor.invocation(),predecessor.operationFingerprint(),
            c.reference(),c.object(),c.livemode(),c.name(),c.created(),c.deletesAfter(),c.frozenTime(),c.status(),c.advancingTarget(),
            predecessor.observedAt(),graceDeadline,target,key);
    }

    public record BoundaryAck(Phase phase,UUID preparationId,ProviderOperation operation,UUID invocation,String claimant,Instant deadline,
            TestClockAck observation,Instant observedAt,Instant acknowledgedAt){
        public BoundaryAck {
            required(phase,preparationId,operation,invocation,deadline,observation,observedAt,acknowledgedAt);
            var dispatch=operation.firstDispatchedAt();
            if(!micro(deadline)||!micro(observedAt)||!micro(acknowledgedAt)||!micro(dispatch)||operation.id()==null
                    ||operation.kind()!=phase.kind()||operation.status()!=Status.ACKNOWLEDGED||operation.version()!=2
                    ||!preparationId.equals(operation.resourceId())||!OwnedFailedGraceAdvance.validClaimant(claimant)
                    ||operation.leaseOwner()!=null||operation.leaseExpiresAt()!=null||!deadline.equals(dispatch.plusSeconds(60))
                    ||!dispatch.plusSeconds(23*3600).equals(operation.retryBefore())||!observation.reference().equals(operation.providerReference())
                    ||observedAt.isBefore(dispatch)||acknowledgedAt.isBefore(observedAt)||!acknowledgedAt.isBefore(deadline))throw denied();
        }
        @Override public String toString(){return "BoundaryAck[redacted]";}
    }

    public record BoundaryReady(UUID id,UUID admissionId,BoundaryAck ack,TestClockReady observation,Instant observedAt){
        public BoundaryReady {
            required(id,admissionId,ack,observation,observedAt);
            if(!micro(observedAt)||!OwnedFailedGraceAdvance.sameClock(ack.observation(),observation)
                    ||observedAt.isBefore(ack.acknowledgedAt())||!observedAt.isBefore(ack.deadline()))throw denied();
        }
        @Override public String toString(){return "BoundaryReady[redacted]";}
    }

    private static void required(Object... values){for(var value:values)if(value==null)throw denied();}
    private static boolean micro(Instant at){return at!=null&&at.getNano()%1000==0;}
    private static IllegalArgumentException denied(){return new IllegalArgumentException("Invalid owned grace boundary evidence");}
}

