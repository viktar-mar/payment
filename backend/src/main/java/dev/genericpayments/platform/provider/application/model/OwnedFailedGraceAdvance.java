package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import java.time.*;
import java.util.*;

/** Descriptions of one original G−1 invocation. Persisted authentication is required at every use. */
public final class OwnedFailedGraceAdvance {
    private OwnedFailedGraceAdvance(){}
    public record PreparationContext(OwnedFailedRenewal.Admission admission,GraceLastSecondPreparation preparation,Failure failure,PaymentAccount account){
        public PreparationContext{
            required(admission,preparation,failure,account);var owner=admission.firstRenewalAdmission().receipt().intent().clock().owner();
            if(!account.id().equals(owner.account())||account.provider()!=PaymentProviderType.STRIPE||account.mode()!=PaymentAccountMode.SANDBOX
                    ||account.status()!=PaymentAccountStatus.ACTIVE||!account.externalAccountId().equals(owner.externalAccount())||!account.credentialsRef().equals(owner.credentialsRef())
                    ||!preparation.admissionId().equals(admission.id())||!preparation.failureClaimId().equals(failure.claimId())||!preparation.billId().equals(failure.billId())
                    ||!preparation.predecessorReadyId().equals(admission.ready().id())||!preparation.graceDeadline().equals(failure.graceDeadline())
                    ||preparation.target()!=OwnedFailedRenewal.graceLastSecondTarget(admission,failure)
                    ||!preparation.fingerprint().equals(OwnedFailedRenewal.graceLastSecondFingerprint(preparation.id(),admission,failure,preparation.target(),preparation.operationKey(),preparation.preparedAt())))throw denied();
        }
        @Override public String toString(){return "PreparationContext[redacted]";}
    }
    public record AdvanceAck(ProviderOperation operation,UUID preparationId,UUID invocation,String claimant,Instant deadline,
            TestClockAck observation,Instant observedAt,Instant acknowledgedAt){
        public AdvanceAck{
            required(operation,preparationId,invocation,deadline,observation,observedAt,acknowledgedAt);micro(deadline,observedAt,acknowledgedAt);
            if(operation.kind()!=ProviderOperation.Kind.OWNED_GRACE_CLOCK_ADVANCE||operation.status()!=ProviderOperation.Status.ACKNOWLEDGED||operation.version()!=2
                    ||!preparationId.equals(operation.resourceId())||!validClaimant(claimant)||operation.leaseOwner()!=null||operation.leaseExpiresAt()!=null
                    ||operation.firstDispatchedAt()==null||operation.retryBefore()==null||!deadline.equals(operation.firstDispatchedAt().plusSeconds(60))
                    ||!operation.retryBefore().equals(operation.firstDispatchedAt().plusSeconds(23*3600))||!observation.reference().equals(operation.providerReference())
                    ||observedAt.isBefore(operation.firstDispatchedAt())||acknowledgedAt.isBefore(observedAt)||!acknowledgedAt.isBefore(deadline))throw denied();
            micro(operation.firstDispatchedAt(),operation.retryBefore());
        }
        @Override public String toString(){return "AdvanceAck[redacted]";}
    }
    public record GraceAdvanceReady(UUID id,UUID admissionId,AdvanceAck ack,TestClockReady observation,Instant observedAt){
        public GraceAdvanceReady{
            required(id,admissionId,ack,observation,observedAt);micro(observedAt);var post=ack.observation();
            if(!sameClock(post,observation)||observedAt.isBefore(ack.acknowledgedAt())||!observedAt.isBefore(ack.deadline()))throw denied();
        }
        @Override public String toString(){return "GraceAdvanceReady[redacted]";}
    }
    public static boolean validClaimant(String value){return value!=null&&!value.isBlank()&&value.length()<=128&&value.codePoints().noneMatch(Character::isISOControl);}
    public static boolean sameClock(TestClockAck a,TestClockReady b){return a.reference().equals(b.reference())&&a.object().equals(b.object())&&a.livemode()==b.livemode()&&a.name().equals(b.name())&&a.created()==b.created()&&a.deletesAfter()==b.deletesAfter();}
    private static void required(Object... values){for(var value:values)if(value==null)throw denied();}
    private static void micro(Instant... values){for(var value:values)if(value.getNano()%1000!=0)throw denied();}
    private static IllegalArgumentException denied(){return new IllegalArgumentException("Invalid owned grace advance evidence");}
}
