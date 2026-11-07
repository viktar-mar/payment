package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.adapter.out.persistence.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalServiceTest.*;

/** Stateful caller transactions and actual FactService; all provider/persistence ports are inert. */
class OwnedFailedRenewalMethodServiceTest {
    @Test void trueConsentReservationPinsPrincipalDebtSequenceAndClaimBeforeAuthorityLocks() {
        var h=new Harness();var r=h.reserve();var f=h.b.failure;var o=h.b.ops.get(r.setupOperationId());
        assertThat(o.authorityScope()).isEqualTo("owned-failed-renewal-method:"+h.b.admission.id());
        assertThat(o.keyDigest()).isEqualTo("digest:"+h.context.idempotencyKey());
        assertThat(o.fingerprint()).isEqualTo(SubscriptionInteractionTransaction.request(h.context,"owned-failed-renewal-method-v1",h.b.admission.id(),f.billId(),f.billVersion(),f.invoice(),f.allocation(),f.paymentIntent(),f.revision(),f.observationSequence(),"SETUP",true));
        assertThat(o.providerReference()).isNull();assertThat(o.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
        assertThat(h.b.trace).containsSubsequence("claim","operations","authority","lifecycle","account","subscription","bill","reservation");
    }
    @Test void falseConsentAndForeignContextCannotCreateReservation() {
        var h=new Harness();assertThatThrownBy(()->h.service.reserveMethod(h.b.admission,h.context,false)).isInstanceOf(IllegalArgumentException.class);assertThat(h.reservation).isNull();
        var f=new Harness();var c=new SubscriptionCommandContext(f.context.actor(),f.context.siteId(),new dev.genericpayments.platform.customers.domain.CustomerId(UUID.randomUUID()),f.context.origin(),"key","request");
        assertThatThrownBy(()->f.service.reserveMethod(f.b.admission,c,true)).isInstanceOf(RuntimeException.class);assertThat(f.reservation).isNull();assertThat(f.b.ops).hasSize(1);
    }
    @Test void pendingRequiresFreshAuthorityAndBothAcksInTheSameTransaction() {
        for(var bad:List.of("authority","acks")){
            var h=new Harness();var r=h.reserve();h.authorized=!bad.equals("authority");h.acks=!bad.equals("acks");
            assertThatThrownBy(()->h.service.recordMethodUpdateReply(r,h.context)).isInstanceOf(IllegalStateException.class);
            assertThat(h.b.ops.get(r.setupOperationId()).status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(h.b.ops.get(r.setupOperationId()).version()).isZero();
            assertThat(h.b.trace).containsSubsequence("rollback","begin","stop","commit");assertThat(h.b.trace.stream().filter("stop"::equals)).hasSize(1);
        }
        var h=new Harness();var r=h.reserve();h.acks=true;h.b.trace.clear();
        var o=h.service.recordMethodUpdateReply(r,h.context);
        assertThat(o.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(o.version()).isEqualTo(1);assertThat(o.providerReference()).isEqualTo("sub_owned");
        assertThat(h.b.trace).containsSubsequence("begin","operations","authority","lifecycle","account","subscription","bill","prepared","commit");
        assertThat(h.b.trace.stream().filter("begin"::equals)).hasSize(1);
    }
    @Test void changedPendingKeyOriginAndPrincipalCannotReuseTheCapturedConsent() {
        for(var bad:List.of("key","origin","principal")){
            var h=new Harness();var r=h.reserve();h.acks=true;
            var actor=bad.equals("principal")?new SubscriptionActor.Admin(new dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin(new dev.genericpayments.platform.admin.domain.AdminUserId(UUID.randomUUID()),"other",new dev.genericpayments.platform.admin.domain.AdminSessionId(UUID.randomUUID()),UTC.plusSeconds(600))):h.context.actor();
            var c=new SubscriptionCommandContext(actor,h.context.siteId(),h.context.customerId(),bad.equals("origin")?"https://other.example":h.context.origin(),bad.equals("key")?"other-key":h.context.idempotencyKey(),"request");
            assertThatThrownBy(()->h.service.recordMethodUpdateReply(r,c)).isInstanceOf(IllegalStateException.class);
            assertThat(h.b.ops.get(r.setupOperationId()).status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(h.b.trace).doesNotContain("prepared");
        }
    }
    @Test void methodUsesActualFactsAndRecoveryPaysOriginalDebtWithUnchangedMapperIdentity() {
        var h=new Harness();var r=h.prepared();var q=h.service.beginMethodRead(r,UUID.randomUUID());var c=h.service.inspectMethodRead(r,q);
        var before=h.b.s;var records=new LinkedHashMap<>(h.b.records);var payments=new LinkedHashSet<>(h.b.cash);var events=new ArrayList<>(h.b.events);var callbacks=h.b.callbacks;var sequence=h.b.sequence;
        var completion=h.service.publishMethod(r,q,h.evidence(),c);
        assertThat(h.b.s).isEqualTo(before);assertThat(h.b.records).isEqualTo(records);assertThat(h.b.cash).isEqualTo(payments);assertThat(h.b.events).isEqualTo(events);assertThat(h.b.callbacks).isEqualTo(callbacks);
        assertThat(h.b.sequence).isEqualTo(sequence+1);assertThat(h.b.ops.get(r.setupOperationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(completion.setupFinalVersion()).isEqualTo(2);
        var recovery=h.service.reserveRecovery(r,completion);h.b.recovery=recovery.association();var o=h.b.ops.get(recovery.association().operationId());var f=r.failure();
        assertThat(o.authorityScope()).isEqualTo(OwnedFailedRenewal.recoveryAuthority(r.admission()));
        assertThat(o.keyDigest()).isEqualTo(OwnedFirstRenewal.fingerprint("owned-failed-renewal-recover-key-v1",r.admission().id()));
        assertThat(o.fingerprint()).isEqualTo("v1.recover."+f.billId()+"."+OwnedFirstRenewal.fingerprint("owned-failed-renewal-recover-v1",r.admission().id(),f.billId(),f.billVersion(),f.invoice(),f.allocation(),f.paymentIntent(),f.revision()));
        var read=h.service.beginRecoveredRead(r,recovery,UUID.randomUUID());var expected=h.service.inspectRecoveredRead(r,read);
        var raw=h.recoveredEvidence();assertThat(raw.snapshot().paymentMethodReady()).isFalse();assertThat(raw.snapshot().operationKey()).isNull();assertThat(raw.snapshot().operationObservation()).isNull();
        var publication=h.service.publishRecovery(r,read,raw,expected);
        assertThat(h.b.s.debt()).isNull();assertThat(h.b.s.revision()).isEqualTo(f.revision()+1);assertThat(h.b.sequence).isEqualTo(f.observationSequence()+2);
        assertThat(h.b.cash).containsExactly("pi_initial","pi_renewal");assertThat(h.b.ops.get(o.id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(publication.publication().billId()).isEqualTo(f.billId());
    }
    @Test void receiptFailureRollsBackFactsBeforeExactlyOneSeparateStop() {
        var h=new Harness();var r=h.prepared();var q=h.service.beginMethodRead(r,UUID.randomUUID());var c=h.service.inspectMethodRead(r,q);var s=h.b.s;var seq=h.b.sequence;var effects=h.b.effects;h.failReceipt=true;
        assertThatThrownBy(()->h.service.publishMethod(r,q,h.evidence(),c)).hasMessage("receipt failed");
        assertThat(h.b.s).isEqualTo(s);assertThat(h.b.sequence).isEqualTo(seq);assertThat(h.b.effects).isEqualTo(effects);assertThat(h.b.ops.get(r.setupOperationId()).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(h.completion).isNull();
        assertThat(h.b.trace).containsSubsequence("completion","rollback","begin","stop","commit");assertThat(h.b.trace.stream().filter("stop"::equals)).hasSize(1);
    }
    @Test void successorPublicationReceiptFailureRestoresOpenDebtAndReservedRecoveryBeforeOneStop() {
        var h=new Harness();var r=h.prepared();var q=h.service.beginMethodRead(r,UUID.randomUUID());var completion=h.service.publishMethod(r,q,h.evidence(),h.service.inspectMethodRead(r,q));
        var recovery=h.service.reserveRecovery(r,completion);h.b.recovery=recovery.association();var read=h.service.beginRecoveredRead(r,recovery,UUID.randomUUID());var c=h.service.inspectRecoveredRead(r,read);
        var s=h.b.s;var seq=h.b.sequence;var cash=new LinkedHashSet<>(h.b.cash);var records=new LinkedHashMap<>(h.b.records);var events=new ArrayList<>(h.b.events);var callbacks=h.b.callbacks;
        h.failReceipt=true;
        assertThatThrownBy(()->h.service.publishRecovery(r,read,h.recoveredEvidence(),c)).hasMessage("receipt failed");
        assertThat(h.b.s).isEqualTo(s);assertThat(h.b.sequence).isEqualTo(seq);assertThat(h.b.cash).isEqualTo(cash);assertThat(h.b.records).isEqualTo(records);assertThat(h.b.events).isEqualTo(events);assertThat(h.b.callbacks).isEqualTo(callbacks);
        assertThat(h.b.ops.get(recovery.association().operationId()).status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(h.completion).isEqualTo(completion);assertThat(h.publication).isNull();
        assertThat(h.b.trace).containsSubsequence("publication","rollback","begin","stop","commit");assertThat(h.b.trace.stream().filter("stop"::equals)).hasSize(1);
    }
    @Test void staleExpiredChangedContextAndSampleDenyWithoutFacts() {
        for(var bad:List.of("expired","sequence","operation","context","billing","mode","ambient")) {
            var h=new Harness();var r=h.prepared();var q=h.service.beginMethodRead(r,UUID.randomUUID());var c=h.service.inspectMethodRead(r,q);var e=h.evidence();var effects=h.b.effects;
            if(bad.equals("expired"))h.b.utc=q.deadlineAt();if(bad.equals("sequence"))h.b.sequence++;if(bad.equals("billing"))h.b.billingSecond++;if(bad.equals("mode"))h.b.isolated=false;if(bad.equals("ambient"))h.b.active=true;
            if(bad.equals("operation")){var o=h.b.ops.get(r.setupOperationId());h.b.ops.put(o.id(),new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),o.version()+1));}
            var expected=bad.equals("context")?new MethodReadContext(c.account(),c.subscription(),c.binding(),c.operation(),c.setupProof(),c.setupStep(),c.methodStep(),new SubscriptionBillingTimePort.Sample(c.billingSample().billingAt().plusSeconds(1),c.billingSample().mode())):c;
            assertThatThrownBy(()->h.service.publishMethod(r,q,e,expected)).isInstanceOf(RuntimeException.class);assertThat(h.b.effects).isEqualTo(effects);assertThat(h.completion).isNull();
        }
    }
    @Test void callerMarkerAndUnboundMethodEvidenceCannotAuthorizeMethodFacts() {
        for(var bad:List.of("marker","setup","method")){
            var h=new Harness();var r=h.prepared();var q=h.service.beginMethodRead(r,UUID.randomUUID());var c=h.service.inspectMethodRead(r,q);var v=h.b.snapshot(false);var effects=h.b.effects;
            if(bad.equals("marker"))v=new Snapshot(v.subscriptionReference(),v.status(),v.billingPrice(),v.period(),null,null,v.invoices(),c.operation().providerOperationKey(),true,0,v.observedAt(),new OperationObservation(c.operation().providerOperationKey(),OperationState.APPLIED));
            var e=new MethodEvidence(v,bad.equals("setup")?"seti_foreign":"seti_owned",bad.equals("method")?"pm_foreign":"pm_owned",h.b.utc);
            assertThatThrownBy(()->h.service.publishMethod(r,q,e,c)).isInstanceOf(IllegalStateException.class);assertThat(h.b.effects).isEqualTo(effects);assertThat(h.completion).isNull();
        }
    }
    @Test void callerInjectedRecoveredMarkerCannotAuthorizeSuccessorFacts() {
        var h=new Harness();var r=h.prepared();var q=h.service.beginMethodRead(r,UUID.randomUUID());var completion=h.service.publishMethod(r,q,h.evidence(),h.service.inspectMethodRead(r,q));
        var recovery=h.service.reserveRecovery(r,completion);h.b.recovery=recovery.association();var read=h.service.beginRecoveredRead(r,recovery,UUID.randomUUID());var expected=h.service.inspectRecoveredRead(r,read);
        var before=h.b.s;var sequence=h.b.sequence;var effects=h.b.effects;var cash=new LinkedHashSet<>(h.b.cash);
        assertThatThrownBy(()->h.service.publishRecovery(r,read,new OwnedFailedRenewal.RecoveredEvidence(h.b.snapshot(true),h.b.utc),expected)).isInstanceOf(IllegalStateException.class);
        assertThat(h.b.s).isEqualTo(before);assertThat(h.b.sequence).isEqualTo(sequence);assertThat(h.b.effects).isEqualTo(effects);assertThat(h.b.cash).isEqualTo(cash);assertThat(h.publication).isNull();
        assertThat(h.b.ops.get(recovery.association().operationId()).status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
    }
    @Test void forgedCompletionCannotLeaveAFreshRecoveryOperationOrAssociation() {
        var h=new Harness();var r=h.prepared();var q=h.service.beginMethodRead(r,UUID.randomUUID());var c=h.service.publishMethod(r,q,h.evidence(),h.service.inspectMethodRead(r,q));
        var forged=new Completion(c.reservationId(),UUID.randomUUID(),c.setupOperationId(),c.setupFinalVersion(),c.setupStepId(),c.setupStepVersion(),c.methodStepId(),c.methodStepVersion(),c.setupIntent(),c.method(),c.revision(),c.observationSequence(),c.completedAt());
        var count=h.b.ops.size();assertThatThrownBy(()->h.service.reserveRecovery(r,forged)).isInstanceOf(IllegalStateException.class);
        assertThat(h.b.ops).hasSize(count);assertThat(h.recovery).isNull();assertThat(h.completion).isEqualTo(c);assertThat(h.b.trace).containsSubsequence("rollback","begin","stop","commit");
    }
    static class Harness {
        final OwnedFailedRenewalServiceTest.Harness b=new OwnedFailedRenewalServiceTest.Harness(true);
        final SubscriptionCommandContext context;final OwnedFailedRenewalMethodService service;
        Reservation reservation;Completion completion;Recovery recovery;Publication publication;Map<Phase,ReadClaim> claims=new HashMap<>();boolean acks,authorized=true,failReceipt;
        Harness(){
            b.fail();b.trace.clear();
            context=new SubscriptionCommandContext(new SubscriptionActor.Admin(new dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin(new dev.genericpayments.platform.admin.domain.AdminUserId(UUID.randomUUID()),"admin",new dev.genericpayments.platform.admin.domain.AdminSessionId(UUID.randomUUID()),UTC.plusSeconds(600))),b.owner.site(),b.owner.customer(),"https://test.example","setup-key","test");
            var tx=new SubscriptionTransactionPort(){public boolean active(){return b.active;}public <T>T required(Supplier<T> work){return requiresNew(work);}public <T>T requiresNew(Supplier<T> work){var oldR=reservation;var oldC=completion;var oldX=recovery;var oldP=publication;var oldClaims=new HashMap<>(claims);try{return b.transactionPort.requiresNew(work);}catch(RuntimeException e){reservation=oldR;completion=oldC;recovery=oldX;publication=oldP;claims=oldClaims;throw e;}}};
            var store=port(OwnedFailedRenewalMethodStore.class,(m,a)->{assertThat(b.active).isTrue();return switch(m){
                case "reserveInTransaction"->{b.trace.add("reservation");if(reservation!=null)throw new IllegalStateException("duplicate");var o=(SubscriptionOperation)a[2];reservation=new Reservation(UUID.randomUUID(),b.admission,b.failure,o.id(),o.fingerprint(),0,b.binding,(SubscriptionBillingTimePort.Sample)a[4],(Instant)a[5]);yield reservation;}
                case "requireReservationInTransaction"->{same((Reservation)a[0]);yield reservation;}
                case "requirePreparedMethodInTransaction"->{same((Reservation)a[0]);b.trace.add("prepared");if(!acks||b.ops.get(reservation.setupOperationId()).status()!=SubscriptionOperation.Status.RESERVED)throw new IllegalStateException("not prepared");yield methodContext();}
                case "claimReadInTransaction"->{same((Reservation)a[0]);var phase=(Phase)a[1];if(claims.containsKey(phase))throw new IllegalStateException("duplicate claim");var q=new ReadClaim(UUID.randomUUID(),reservation.id(),phase,(UUID)a[2],(Instant)a[5],((Instant)a[5]).plusSeconds(30),(Fence)a[3],(SubscriptionBillingTimePort.Sample)a[4]);claims.put(phase,q);yield q;}
                case "requireReadInTransaction"->{read((ReadClaim)a[1]);yield a[1];}
                case "requireMethodContextInTransaction"->{read((ReadClaim)a[1]);yield methodContext();}
                case "requireRecoveredContextInTransaction"->{read((ReadClaim)a[1]);yield new RecoveredReadContext(b.account,b.s,b.binding,b.ops.get(recovery.association().operationId()),completion,recovery,reservation.billingSample());}
                case "recordCompletionInTransaction"->{b.trace.add("completion");if(failReceipt)throw new IllegalStateException("receipt failed");var q=(ReadClaim)a[1];assertThat(b.sequence).isEqualTo(reservation.failure().observationSequence()+1);assertThat(b.ops.get(reservation.setupOperationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);completion=new Completion(reservation.id(),q.id(),reservation.setupOperationId(),2,id(91),2,id(92),2,"seti_owned","pm_owned",b.s.revision(),b.sequence,b.utc);yield completion;}
                case "requireCompletionInTransaction"->{same((Reservation)a[0]);if(completion==null||!completion.equals(a[1]))throw new IllegalStateException("completion unavailable");yield completion;}
                case "associateRecoveryInTransaction"->{if(!Objects.equals(completion,a[1]))throw new IllegalStateException("completion unavailable");var o=(SubscriptionOperation)a[2];recovery=new Recovery(reservation.id(),new OwnedFailedRenewal.Recovery(b.admission.id(),o.id(),b.failure.billId(),b.failure.allocation(),b.failure.paymentIntent(),b.failure.revision(),0,b.utc),b.sequence);yield recovery;}
                case "requireRecoveryInTransaction"->{if(recovery==null||!recovery.equals(a[1]))throw new IllegalStateException("recovery unavailable");yield recovery;}
                case "requireMethodFactInTransaction","requireRecoveryFactInTransaction"->{var c=(dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command)a[0];var phase=m.equals("requireMethodFactInTransaction")?Phase.METHOD:Phase.RECOVERED;var q=claims.get(phase);read(q);assertThat(c.factKey()).isEqualTo(OwnedFailedRenewalMethod.factKey(q));assertThat(c.fence()).isEqualTo(q.fence());
                    if(phase==Phase.RECOVERED){assertThat(c.snapshot().paymentMethodReady()).isFalse();assertThat(c.snapshot().operationKey()).isEqualTo(b.ops.get(recovery.association().operationId()).providerOperationKey());assertThat(c.snapshot().operationObservation()).isEqualTo(new OperationObservation(c.snapshot().operationKey(),OperationState.APPLIED));}yield null;}
                case "recordPublicationInTransaction"->{b.trace.add("publication");if(failReceipt)throw new IllegalStateException("receipt failed");assertThat(b.s.debt()).isNull();publication=new Publication(reservation.id(),new OwnedFailedRenewal.Publication(b.admission.id(),((ReadClaim)a[1]).id(),recovery.association().operationId(),b.failure.billId(),UUID.randomUUID(),b.s.revision(),b.utc),b.sequence);yield publication;}
                default->throw new AssertionError(m);
            };});
            b.methodProof=new OwnedFailedRenewalMethodFactProof(store,tx,b.timePort);b.successorProof=new OwnedFailedRenewalMethodRecoveryFactProof(store,tx,b.timePort);
            var failed=port(OwnedFailedRenewalStore.class,(m,a)->{assertThat(b.active).isTrue();return switch(m){case "requireFailureInTransaction"->b.failure;case "stopInTransaction"->{b.trace.add("stop");b.stopped=true;yield null;}default->throw new AssertionError(m);};});
            service=new OwnedFailedRenewalMethodService(store,failed,tx,b.operationsPort,(site,customer)->b.trace.add("lifecycle"),id->{b.trace.add("account");return Optional.of(b.account);},b.subscriptionsPort,b.billsPort,new SubscriptionReadPreparation(b.operationsPort,port(TrialEligibilityStore.class,(m,a)->Optional.empty())),b.observationsPort,b.factsPort,port(TrialEligibilityStore.class,(m,a)->Optional.empty()),b.timePort,b.clockPort,port(StripeBillingBindingPort.class,(m,a)->Optional.of(b.binding)),port(SubscriptionAuthorityPort.class,(m,a)->{assertThat(b.active).isTrue();b.trace.add("authority");if(!authorized)throw new IllegalStateException("authority revoked");return null;}),value->"digest:"+value);
        }
        Reservation reserve(){return service.reserveMethod(b.admission,context,true);}
        Reservation prepared(){var r=reserve();acks=true;b.methodSetup=new SubscriptionBillingProofStore.Setup(r.setupOperationId(),b.s.id(),b.account.id(),id(91),"seti_owned","pm_owned");service.recordMethodUpdateReply(r,context);return r;}
        void same(Reservation r){if(reservation==null||!reservation.equals(r)||b.stopped)throw new IllegalStateException("reservation unavailable");}
        void read(ReadClaim q){same(reservation);if(q==null||!q.equals(claims.get(q.phase()))||!b.utc.isBefore(q.deadlineAt())||q.fence().observationSequence()!=b.sequence)throw new IllegalStateException("read unavailable");}
        MethodReadContext methodContext(){var o=b.ops.get(reservation.setupOperationId());var setup=b.methodSetup==null?new SubscriptionBillingProofStore.Setup(o.id(),b.s.id(),b.account.id(),id(91),"seti_owned","pm_owned"):b.methodSetup;return new MethodReadContext(b.account,b.s,b.binding,o,setup,step(o,false),step(o,true),reservation.billingSample());}
        ProviderOperation step(SubscriptionOperation o,boolean method){return new ProviderOperation(id(method?92:91),b.account.id(),b.account.externalAccountId(),b.account.credentialsRef(),method?ProviderOperation.Kind.SUBSCRIPTION_UPDATE:ProviderOperation.Kind.SETUP_INTENT_CREATE,o.id(),o.providerOperationKey()+(method?":METHOD":":SETUP"),"fingerprint",ProviderOperation.Status.ACKNOWLEDGED,method?"sub_owned":"seti_owned",b.utc,b.utc.plusSeconds(100),null,null,2);}
        MethodEvidence evidence(){return new MethodEvidence(b.snapshot(false),"seti_owned","pm_owned",b.utc);}
        OwnedFailedRenewal.RecoveredEvidence recoveredEvidence(){var v=b.snapshot(true);var raw=new Snapshot(v.subscriptionReference(),v.status(),v.billingPrice(),v.period(),v.trial(),v.cancellationAt(),v.invoices(),null,false,v.providerRevision(),v.observedAt(),null,v.recoveryObservations(),v.trialEvidence(),v.externalWithdrawalEvidence());return new OwnedFailedRenewal.RecoveredEvidence(raw,b.utc);}
    }
}
