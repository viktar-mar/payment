package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual lifecycle/fact publication, handwritten caller transactions and inert external ports only. */
class OwnedRecoveryPreparationTest {
    // Foreign rows exist, but neither caller receipt nor forged reservation may choose a lock target.
    @Test void existingForeignRecoveryCannotReachAnOperationLock(){
        var h=completed(true);var foreign=foreignOperation(h);var r=h.recovery;var a=r.association();
        var forged=new Recovery(r.reservationId(),new OwnedFailedRenewal.Recovery(a.admissionId(),foreign.id(),a.billId(),a.allocation(),a.paymentIntent(),a.expectedRevision(),a.operationVersion(),a.associatedAt()),r.observationSequence());
        h.b.operationLockTargets.clear();h.authorityTargets.clear();denied(h,h.context,h.completion,forged,"existing foreign RECOVER");
        assertThat(h.b.operationLockTargets).doesNotContain(foreign.id());
        assertThat(inspect(h,h.context).recovery()).isEqualTo(h.recovery);
    }
    @Test void forgedReservationCannotReachItsExistingForeignSetupLocator(){
        var h=completed(true);var foreign=foreignOperation(h);var r=h.reservation;
        var forged=new Reservation(r.id(),r.admission(),r.failure(),foreign.id(),r.requestFingerprint(),r.initialOperationVersion(),r.binding(),r.billingSample(),r.reservedAt());
        var before=state(h);h.b.trace.clear();h.b.operationLockTargets.clear();h.authorityTargets.clear();
        assertThatThrownBy(()->h.service.inspectBrowserRecoveryPreparation(forged,h.completion,h.recovery,h.context)).isInstanceOf(RuntimeException.class);
        assertThat(h.b.operationLockTargets).isEmpty();assertThat(h.authorityTargets).isEmpty();assertThat(state(h)).isEqualTo(before);noEffects(h);
        assertThat(inspect(h,h.context).reservation()).isEqualTo(r);
    }
    @Test void foreignOwnerContextRefusesBeforeAnyTransactionOrOperationOrAuthorityLock(){
        var h=completed(true);var s=session(h);
        var foreign=new CustomerSessionContext(s.sessionId(),new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),new CustomerId(UUID.randomUUID()),s.origin(),s.scopes(),s.expiresAt());
        var context=new SubscriptionCommandContext(new SubscriptionActor.Customer(foreign),foreign.siteId(),foreign.customerId(),foreign.origin(),h.context.idempotencyKey(),"foreign-owner");
        h.b.operationLockTargets.clear();h.authorityTargets.clear();denied(h,context,h.completion,h.recovery,"foreign owner");
        assertThat(h.b.trace).doesNotContain("begin");assertThat(h.b.operationLockTargets).isEmpty();assertThat(h.authorityTargets).isEmpty();
        assertThat(inspect(h,h.context).reservation()).isEqualTo(h.reservation);
    }
    @Test void originalOwnerCarryingAnotherExistingSessionIdRefusesBeforeAnyAuthorityTarget(){
        var h=completed(true);var foreign=new OwnedFailedRenewalMethodServiceTest.Harness(true);var s=session(h);
        h.secondLiveSession=session(foreign); // A real fixture session, with no authority from this reservation.
        var forged=new CustomerSessionContext(h.secondLiveSession.sessionId(),s.siteId(),s.customerId(),s.origin(),s.scopes(),s.expiresAt());
        var context=new SubscriptionCommandContext(new SubscriptionActor.Customer(forged),s.siteId(),s.customerId(),s.origin(),h.context.idempotencyKey(),"foreign-session");
        h.b.operationLockTargets.clear();h.authorityTargets.clear();denied(h,context,h.completion,h.recovery,"foreign session ID");
        assertThat(h.authorityTargets).isEmpty();assertThat(h.b.operationLockTargets).isEmpty();
        assertThat(inspect(h,h.context).enrollment()).isEqualTo(h.enrollment);
    }
    private static SubscriptionOperation foreignOperation(OwnedFailedRenewalMethodServiceTest.Harness h){
        var o=h.b.ops.get(h.recovery.association().operationId());
        var foreign=new SubscriptionOperation(UUID.randomUUID(),new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),new CustomerId(UUID.randomUUID()),UUID.randomUUID(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),o.updatedAt(),o.version());
        h.b.ops.put(foreign.id(),foreign);return foreign;
    }
    // Catches using the old SETUP/debt-delta-0 guard, mutation, claims, stops or cached authority.
    @Test void completedOriginalBrowserFlowReturnsExactDescriptionTwiceWithoutAnyWrites(){
        var h=completed(true);var before=state(h);
        for(int i=0;i<2;i++){
            h.b.trace.clear();h.b.operationLockTargets.clear();var c=inspect(h,h.context);
            assertThat(c.reservation()).isEqualTo(h.reservation);assertThat(c.enrollment()).isEqualTo(h.enrollment);
            assertThat(c.account()).isEqualTo(h.b.account);assertThat(c.subscription()).isEqualTo(h.b.s);
            assertThat(c.setupOperation()).isEqualTo(h.b.ops.get(h.reservation.setupOperationId()));
            assertThat(c.setupOperation().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
            assertThat(c.recoveryOperation()).isEqualTo(h.b.ops.get(h.recovery.association().operationId()));
            assertThat(c.recoveryOperation().status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
            assertThat(c.binding()).isEqualTo(h.reservation.binding());assertThat(c.completion()).isEqualTo(h.completion);
            assertThat(c.recovery()).isEqualTo(h.recovery);assertThat(c.billingSample()).isEqualTo(h.reservation.billingSample());
            assertThat(state(h)).isEqualTo(before);assertThat(h.b.trace.stream().filter("begin"::equals)).hasSize(1);
            assertThat(h.b.trace).containsSubsequence("begin","operations","authority","lifecycle","account","subscription","bill","recovery-enrollment","authority","commit");
            assertThat(h.b.trace).containsSubsequence("begin","recovery-identity","operations","authority");
            assertThat(h.b.operationLockTargets).isSorted().contains(h.recovery.association().operationId());
            noEffects(h);
        }
        assertThat(h.b.sequence).isEqualTo(h.reservation.failure().observationSequence()+1);
        assertThat(h.b.s.debt()).isNotNull();assertThat(h.publication).isNull();assertThat(h.claims).containsOnlyKeys(Phase.METHOD);
    }
    // Catches trusting a caller's receipt instead of independently requiring each durable original.
    @Test void missingOrForgedCompletionAndRecoveryNeverGrantAuthority(){
        for(var bad:List.of("missing-completion","missing-recovery","completion-claim","completion-method","recovery-operation","recovery-sequence")){
            var h=completed(true);var c=h.completion;var r=h.recovery;
            if(bad.equals("missing-completion"))h.completion=null;if(bad.equals("missing-recovery"))h.recovery=null;
            if(bad.startsWith("completion-"))c=new Completion(c.reservationId(),bad.equals("completion-claim")?UUID.randomUUID():c.claimId(),c.setupOperationId(),c.setupFinalVersion(),c.setupStepId(),c.setupStepVersion(),c.methodStepId(),c.methodStepVersion(),c.setupIntent(),bad.equals("completion-method")?"pm_forged":c.method(),c.revision(),c.observationSequence(),c.completedAt());
            if(bad.startsWith("recovery-")){var a=r.association();r=new Recovery(r.reservationId(),new OwnedFailedRenewal.Recovery(a.admissionId(),bad.equals("recovery-operation")?UUID.randomUUID():a.operationId(),a.billId(),a.allocation(),a.paymentIntent(),a.expectedRevision(),a.operationVersion(),a.associatedAt()),bad.equals("recovery-sequence")?r.observationSequence()+1:r.observationSequence());}
            denied(h,h.context,c,r,bad);
        }
    }
    // Catches omitting identity checks even if a persistence port returns a mismatched durable description.
    @Test void returnedDurableReceiptsMustDescribeThisExactReservationSetupAndDebt(){
        for(var bad:List.of("completion-reservation","completion-setup","completion-version","completion-revision","completion-sequence","recovery-reservation","admission","bill","allocation","payment-intent","revision","association-version","sequence")){
            var h=completed(true);var c=h.completion;var r=h.recovery;var a=r.association();
            if(bad.startsWith("completion-"))h.completion=new Completion(bad.equals("completion-reservation")?UUID.randomUUID():c.reservationId(),c.claimId(),bad.equals("completion-setup")?UUID.randomUUID():c.setupOperationId(),bad.equals("completion-version")?c.setupFinalVersion()+1:c.setupFinalVersion(),c.setupStepId(),c.setupStepVersion(),c.methodStepId(),c.methodStepVersion(),c.setupIntent(),c.method(),bad.equals("completion-revision")?c.revision()+1:c.revision(),bad.equals("completion-sequence")?c.observationSequence()+1:c.observationSequence(),c.completedAt());
            else h.recovery=new Recovery(bad.equals("recovery-reservation")?UUID.randomUUID():r.reservationId(),new OwnedFailedRenewal.Recovery(bad.equals("admission")?UUID.randomUUID():a.admissionId(),a.operationId(),bad.equals("bill")?UUID.randomUUID():a.billId(),bad.equals("allocation")?"inpay_forged":a.allocation(),bad.equals("payment-intent")?"pi_forged":a.paymentIntent(),bad.equals("revision")?a.expectedRevision()+1:a.expectedRevision(),bad.equals("association-version")?a.operationVersion()+1:a.operationVersion(),a.associatedAt()),bad.equals("sequence")?r.observationSequence()+1:r.observationSequence());
            denied(h,h.context,h.completion,h.recovery,bad);
        }
    }
    // Catches allowing a changed SETUP or RECOVER operation under otherwise valid receipts.
    @Test void bothOperationsRetainTheirOriginalStatusVersionAuthorityKeyFingerprintPolicyAndReference(){
        for(var which:List.of("setup","recovery"))for(var bad:List.of("kind","status","version","authority","key","fingerprint","provider-key","policy","revision","reference","failure")){
            var h=completed(true);var id=which.equals("setup")?h.reservation.setupOperationId():h.recovery.association().operationId();var o=h.b.ops.get(id);
            h.b.ops.put(id,new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),bad.equals("kind")?SubscriptionOperation.Kind.CHANGE:o.kind(),bad.equals("status")?SubscriptionOperation.Status.PROCESSING:o.status(),bad.equals("authority")?"foreign":o.authorityScope(),bad.equals("key")?"foreign":o.keyDigest(),bad.equals("fingerprint")?"foreign":o.fingerprint(),bad.equals("provider-key")?"foreign":o.providerOperationKey(),bad.equals("revision")?o.expectedRevision()+1:o.expectedRevision(),bad.equals("policy")?o.policyVersion()+1:o.policyVersion(),bad.equals("reference")?"sub_foreign":o.providerReference(),bad.equals("failure")?"FOREIGN":null,o.createdAt(),o.updatedAt(),bad.equals("version")?o.version()+1:o.version()));
            denied(h,h.context,h.completion,h.recovery,which+" "+bad);
        }
    }
    // Catches dropping the shared current/debt/fence/sample checks for this later phase.
    @Test void debtBillSequenceSampleBindingAndStoppedAdmissionDriftRefuseWithoutEffects(){
        for(var bad:List.of("debt","bill","sequence","billing","mode","binding","stopped","authority")){
            var h=completed(true);
            if(bad.equals("debt")){var s=h.b.s;h.b.s=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),null,s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
            if(bad.equals("bill")){var b=h.b.records.get("in_renewal");h.b.records.put("in_renewal",new BillingRecord(b.id(),b.siteId(),b.subscriptionId(),b.accountId(),b.providerInvoiceReference(),b.kind(),b.billedPrice(),b.previousPrice(),b.period(),b.amountDueMinor(),b.currency(),b.settlement(),b.version()+1,b.createdAt(),b.updatedAt()));}
            if(bad.equals("sequence"))h.b.sequence++;if(bad.equals("billing"))h.b.billingSecond++;if(bad.equals("mode"))h.b.isolated=false;
            if(bad.equals("binding")){var b=h.b.binding;h.b.binding=new StripeBillingBindingPort.CustomerBinding(b.id(),b.accountId(),b.siteId(),b.customerId(),b.providerReference(),b.creationOperationId(),b.version()+1);}
            if(bad.equals("stopped"))h.b.stopped=true;if(bad.equals("authority"))h.authorized=false;
            denied(h,h.context,h.completion,h.recovery,bad);
        }
    }
    // Catches accepting a new session, changed key, admin or broadened/inexact original enrollment.
    @Test void onlyTheOriginalLiveManagementSessionOriginScopesExpiryAndKeyAreAccepted(){
        for(var bad:List.of("session","origin","site","customer","scope","expiry","expiry-nanosecond","key","missing-key","admin","expired","ambient","revoked")){
            var h=completed(true);var s=session(h);var c=h.context;
            if(Set.of("session","origin","site","customer","scope","expiry","expiry-nanosecond").contains(bad)){
                var changed=new CustomerSessionContext(bad.equals("session")?new CustomerSessionId(UUID.randomUUID()):s.sessionId(),bad.equals("site")?new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()):s.siteId(),bad.equals("customer")?new CustomerId(UUID.randomUUID()):s.customerId(),bad.equals("origin")?"https://foreign.test":s.origin(),bad.equals("scope")?Set.of(CustomerSessionScope.SUBSCRIPTION_READ):s.scopes(),bad.equals("expiry")?s.expiresAt().plusSeconds(1):bad.equals("expiry-nanosecond")?s.expiresAt().plusNanos(1):s.expiresAt());
                c=new SubscriptionCommandContext(new SubscriptionActor.Customer(changed),changed.siteId(),changed.customerId(),changed.origin(),c.idempotencyKey(),"changed");
            }
            if(bad.equals("key")||bad.equals("missing-key"))c=new SubscriptionCommandContext(c.actor(),c.siteId(),c.customerId(),c.origin(),bad.equals("key")?"foreign-key":null,"changed");
            if(bad.equals("admin"))c=new OwnedFailedRenewalMethodServiceTest.Harness().context;
            if(bad.equals("expired"))h.b.utc=s.expiresAt();if(bad.equals("ambient"))h.b.active=true;if(bad.equals("revoked"))h.liveSession=false;
            denied(h,c,h.completion,h.recovery,bad);if(bad.equals("ambient"))assertThat(h.b.trace).doesNotContain("begin");
        }
        var h=completed(true);var s=session(h);h.secondLiveSession=new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),s.siteId(),s.customerId(),s.origin(),s.scopes(),s.expiresAt());h.liveSession=false;
        var replacement=new SubscriptionCommandContext(new SubscriptionActor.Customer(h.secondLiveSession),s.siteId(),s.customerId(),s.origin(),h.context.idempotencyKey(),"replacement");
        denied(h,replacement,h.completion,h.recovery,"second live session with original key");
    }
    // Catches fallback to legacy enrollment or omission of independently committed browser evidence.
    @Test void nonBrowserMissingOrUncommittedBrowserEvidenceAndForgedEnrollmentHaveNoFallback(){
        var legacy=completed(false);denied(legacy,legacy.context,legacy.completion,legacy.recovery,"non-browser");
        for(var bad:List.of("disabled","consumption","uncommitted","lineage","session","origin","expiry")){
            var h=completed(true);var e=h.enrollment;
            if(bad.equals("disabled"))h.recoveryPreparationEnabled=false;if(bad.equals("consumption"))h.browserConsumed=false;if(bad.equals("uncommitted"))h.browserConsumptionUncommitted=true;if(bad.equals("lineage"))h.recoveryEnrollmentDenied=true;
            if(Set.of("session","origin","expiry").contains(bad))h.returnedRecoveryEnrollment=new OwnedMethodBrowser.Enrollment(bad.equals("session")?UUID.randomUUID():e.sessionId(),bad.equals("origin")?"https://foreign.test":e.origin(),bad.equals("expiry")?e.sessionExpiresAt().plusNanos(1):e.sessionExpiresAt());
            denied(h,h.context,h.completion,h.recovery,bad);
        }
    }
    // Catches missing post-wait authentication and microsecond truncation at the final boundary.
    @Test void finalStoreAndAuthorityWaitsCannotExtendTheExactOriginalManagementSession(){
        for(var boundary:List.of("store","authority"))for(var bad:List.of("expiry","expiry-nanosecond","revoked","authority")){
            var h=completed(true);Runnable change=()->{if(bad.equals("expiry"))h.b.utc=session(h).expiresAt();if(bad.equals("expiry-nanosecond"))h.b.utc=session(h).expiresAt().plusNanos(1);if(bad.equals("revoked"))h.liveSession=false;if(bad.equals("authority"))h.authorized=false;};
            if(boundary.equals("store"))h.afterRecoveryEnrollment=change;
            else h.afterAuthority=()->{if(h.b.trace.contains("recovery-enrollment"))change.run();};
            denied(h,h.context,h.completion,h.recovery,boundary+" "+bad);
        }
    }
    @Test void descriptionRequiresEveryFieldAndIsolatedSampleAndRedactsAllIdentifiers(){
        var h=completed(true);var c=inspect(h,h.context);
        for(int i=0;i<10;i++){int n=i;assertThatThrownBy(()->new RecoveryPreparationContext(n==0?null:c.reservation(),n==1?null:c.enrollment(),n==2?null:c.account(),n==3?null:c.subscription(),n==4?null:c.setupOperation(),n==5?null:c.recoveryOperation(),n==6?null:c.binding(),n==7?null:c.completion(),n==8?null:c.recovery(),n==9?null:c.billingSample())).isInstanceOf(IllegalArgumentException.class);}
        assertThatThrownBy(()->new RecoveryPreparationContext(c.reservation(),c.enrollment(),c.account(),c.subscription(),c.setupOperation(),c.recoveryOperation(),c.binding(),c.completion(),c.recovery(),new SubscriptionBillingTimePort.Sample(c.billingSample().billingAt(),SubscriptionBillingTimePort.Mode.ORDINARY))).isInstanceOf(IllegalArgumentException.class);
        assertThat(c.toString()).isEqualTo("RecoveryPreparationContext[redacted]");
    }
    @Test void storeRecoveryEnrollmentDefaultsToDenied(){
        var h=completed(true);
        var store=(OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(OwnedFailedRenewalMethodStore.class.getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},(proxy,method,args)->{if(!method.isDefault())throw new AssertionError("Unexpected abstract method");return InvocationHandler.invokeDefault(proxy,method,args);});
        assertThatThrownBy(()->store.requireBrowserRecoveryEnrollmentInTransaction(h.reservation,session(h),h.b.utc)).isInstanceOf(IllegalStateException.class);
    }
    @Test void storeRecoveryIdentityIndependentlyDefaultsToDenied(){
        var h=completed(true);
        var store=(OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(OwnedFailedRenewalMethodStore.class.getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},(proxy,method,args)->{if(!method.isDefault())throw new AssertionError("Unexpected abstract method");return InvocationHandler.invokeDefault(proxy,method,args);});
        assertThatThrownBy(()->store.requireBrowserRecoveryIdentityInTransaction(h.reservation,session(h))).isInstanceOf(IllegalStateException.class);
    }
    @Test void immutableIdentityPreflightCannotReplaceFinalLiveEnrollmentOrExpiryChecks(){
        for(var drift:List.of("session","origin","expiry","revoked","expired")){
            var h=completed(true);var e=h.enrollment;
            h.afterRecoveryIdentity=()->{
                if(Set.of("session","origin","expiry").contains(drift))h.enrollment=new OwnedMethodBrowser.Enrollment(drift.equals("session")?UUID.randomUUID():e.sessionId(),drift.equals("origin")?"https://foreign.test":e.origin(),drift.equals("expiry")?e.sessionExpiresAt().plusNanos(1):e.sessionExpiresAt());
                if(drift.equals("revoked"))h.liveSession=false;if(drift.equals("expired"))h.b.utc=e.sessionExpiresAt();
            };
            denied(h,h.context,h.completion,h.recovery,"after immutable preflight: "+drift);
        }
    }
    private static OwnedFailedRenewalMethodServiceTest.Harness completed(boolean browser){
        var h=new OwnedFailedRenewalMethodServiceTest.Harness(true,"alias",OwnedFailedRenewalServiceTest.UTC.plusSeconds(600).plusNanos(123456789));
        var r=browser?h.service.reserveBrowserMethod(h.b.admission,h.context,true):h.reserve();
        // External ACKs are inert; lifecycle and successful completion are produced only by actual service/facts.
        h.acks=true;h.browserConsumed=browser;h.recoveryPreparationEnabled=true;
        h.b.methodSetup=new Setup(r.setupOperationId(),h.b.s.id(),h.b.account.id(),OwnedFailedRenewalServiceTest.id(91),"seti_owned","pm_owned");
        h.service.recordMethodUpdateReply(r,h.context);var q=h.service.beginMethodRead(r,UUID.randomUUID());
        var c=h.service.publishMethod(r,q,h.evidence(),h.service.inspectMethodRead(r,q));
        var recovery=h.service.reserveRecovery(r,c);h.b.recovery=recovery.association();h.b.trace.clear();return h;
    }
    private static RecoveryPreparationContext inspect(OwnedFailedRenewalMethodServiceTest.Harness h,SubscriptionCommandContext c){return h.service.inspectBrowserRecoveryPreparation(h.reservation,h.completion,h.recovery,c);}
    private static CustomerSessionContext session(OwnedFailedRenewalMethodServiceTest.Harness h){return ((SubscriptionActor.Customer)h.context.actor()).session();}
    private static void denied(OwnedFailedRenewalMethodServiceTest.Harness h,SubscriptionCommandContext context,Completion c,Recovery r,String label){var before=state(h);h.b.trace.clear();assertThatThrownBy(()->h.service.inspectBrowserRecoveryPreparation(h.reservation,c,r,context)).as(label).isInstanceOf(RuntimeException.class);assertThat(state(h)).as(label).isEqualTo(before);noEffects(h);}
    private static void noEffects(OwnedFailedRenewalMethodServiceTest.Harness h){assertThat(h.b.trace).doesNotContain("stop","prepared","claim","reservation","facts","completion","publication","binding-write");}
    private static List<Object> state(OwnedFailedRenewalMethodServiceTest.Harness h){return Arrays.asList(h.b.s,new LinkedHashMap<>(h.b.ops),new LinkedHashMap<>(h.b.records),new LinkedHashSet<>(h.b.cash),new ArrayList<>(h.b.events),h.b.callbacks,h.b.sequence,h.b.effects,h.b.stopped,h.reservation,h.enrollment,h.completion,h.recovery,h.publication,new HashMap<>(h.claims),h.b.methodSetup,new LinkedHashMap<>(h.providerSteps));}
}
