package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeRequestFingerprint;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual authority/service and opt-in inert caller transactions; explicit unbound SETUP-only evidence. */
class OwnedMethodBindingPreparationTest {
    @Test void committedOriginalUnboundSetupIsInspectableBeforeAnyMethodWithoutChangingState(){
        var h=unbound();var before=state(h);
        var c=h.service.inspectBrowserBindingPreparation(h.reservation,h.context);
        assertThat(c.original().reservation()).isEqualTo(h.reservation);assertThat(c.original().enrollment()).isEqualTo(h.enrollment);
        assertThat(c.original().account()).isEqualTo(h.b.account);assertThat(c.original().subscription()).isEqualTo(h.b.s);
        assertThat(c.original().operation()).isEqualTo(h.b.ops.get(h.reservation.setupOperationId()));assertThat(c.original().binding()).isEqualTo(h.b.binding);
        assertThat(c.original().billingSample()).isEqualTo(h.reservation.billingSample());assertThat(c.setupProof()).isEqualTo(h.b.methodSetup);
        assertThat(c.setupProof().method()).isNull();assertThat(c.setupStep()).isEqualTo(step(h));
        assertThat(h.acks).isFalse();assertThat(h.providerSteps).hasSize(1);assertThat(state(h)).isEqualTo(before);
        assertThat(h.b.trace).containsSubsequence("begin","setup-enrollment","unbound-setup","authority","commit");
        assertThat(h.b.trace.stream().filter("begin"::equals)).hasSize(1);noEffects(h);
    }
    @Test void separateFreshBindingReturnsActualProofAndOnlyChangesOriginalMethodColumn(){
        var h=unbound();var c=h.service.inspectBrowserBindingPreparation(h.reservation,h.context);var before=business(h);var proof=c.setupProof();h.b.trace.clear();
        var bound=h.service.bindBrowserMethod(c,h.context,"pm_confirmed");
        assertThat(bound).isEqualTo(new Setup(proof.operation(),proof.subscription(),proof.account(),proof.step(),proof.reference(),"pm_confirmed"));
        assertThat(h.b.methodSetup).isEqualTo(bound);assertThat(business(h)).isEqualTo(before);
        assertThat(h.b.trace).containsSubsequence("begin","unbound-setup","binding-write","authority","commit");
        assertThat(h.b.trace.stream().filter("begin"::equals)).hasSize(1);assertThat(h.providerSteps).hasSize(1);noEffects(h);
        assertThatThrownBy(()->h.service.inspectBrowserBindingPreparation(h.reservation,h.context)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->h.service.bindBrowserMethod(c,h.context,"pm_other")).isInstanceOf(RuntimeException.class);
        assertThat(h.b.methodSetup).isEqualTo(bound);assertThat(business(h)).isEqualTo(before);
    }
    @Test void missingBoundUncommittedOrNoncanonicalEvidenceHasNoFallback(){
        for(var bad:List.of("disabled","missing-consumption","uncommitted-consumption","proof","bound","ack","fingerprint","chronology")){
            var h=unbound();
            if(bad.equals("disabled"))h.bindingEnabled=false;if(bad.equals("missing-consumption"))h.browserConsumed=false;
            if(bad.equals("uncommitted-consumption"))h.browserConsumptionUncommitted=true;
            if(bad.equals("proof"))h.b.methodSetup=null;if(bad.equals("bound")){var p=h.b.methodSetup;h.b.methodSetup=new Setup(p.operation(),p.subscription(),p.account(),p.step(),p.reference(),"pm_existing");}
            if(bad.equals("ack"))h.providerSteps.clear();if(bad.equals("fingerprint")||bad.equals("chronology"))h.unboundSetupDenied=true;
            denied(h,h.context,bad);
        }
    }
    @Test void serviceRejectsForeignOrBoundReceiptEvenWhenUnboundStoreReturnsIt(){
        for(var bad:List.of("enrollment","operation","subscription","account","step","reference","bound")){
            var h=unbound();var p=h.b.methodSetup;var e=h.enrollment;
            if(bad.equals("enrollment"))e=new OwnedMethodBrowser.Enrollment(UUID.randomUUID(),e.origin(),e.sessionExpiresAt());
            h.returnedUnboundSetup=new BoundSetup(e,new Setup(bad.equals("operation")?UUID.randomUUID():p.operation(),bad.equals("subscription")?UUID.randomUUID():p.subscription(),
                bad.equals("account")?new PaymentAccountId(UUID.randomUUID()):p.account(),bad.equals("step")?UUID.randomUUID():p.step(),
                bad.equals("reference")?"seti_foreign":p.reference(),bad.equals("bound")?"pm_foreign":null),step(h));
            denied(h,h.context,bad);
        }
    }
    @Test void ackRequiresExactOriginalIdentityCanonicalVersionWindowAndClearedLease(){
        for(var bad:List.of("id","account","external-account","credential","kind","resource","key","status","reference","version","first","before-reservation","future","window","lease-owner","lease-expiry")){
            var h=unbound();h.returnedUnboundSetup=new BoundSetup(h.enrollment,h.b.methodSetup,changedStep(step(h),bad));denied(h,h.context,bad);
        }
    }
    @Test void originalIdentityKeyScopeAndExactEnrollmentAreRequiredAndNeverStopTheHolder(){
        for(var bad:List.of("session","origin","customer","site","expiry","expiry-nanosecond","scope","admin","key","missing-key","expired","ambient","revoked")){
            var h=unbound();var s=session(h);var c=h.context;
            if(Set.of("session","origin","customer","site","expiry","expiry-nanosecond","scope").contains(bad)){
                var changed=new CustomerSessionContext(bad.equals("session")?new CustomerSessionId(UUID.randomUUID()):s.sessionId(),
                    bad.equals("site")?new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()):s.siteId(),bad.equals("customer")?new CustomerId(UUID.randomUUID()):s.customerId(),
                    bad.equals("origin")?"https://foreign.test":s.origin(),bad.equals("scope")?Set.of(CustomerSessionScope.SUBSCRIPTION_READ):s.scopes(),
                    bad.equals("expiry")?s.expiresAt().plusSeconds(1):bad.equals("expiry-nanosecond")?s.expiresAt().plusNanos(1):s.expiresAt());
                c=new SubscriptionCommandContext(new SubscriptionActor.Customer(changed),changed.siteId(),changed.customerId(),changed.origin(),c.idempotencyKey(),"changed");
            }
            if(bad.equals("admin"))c=new OwnedFailedRenewalMethodServiceTest.Harness().context;
            if(bad.equals("key")||bad.equals("missing-key"))c=new SubscriptionCommandContext(c.actor(),c.siteId(),c.customerId(),c.origin(),bad.equals("key")?"foreign":null,"changed");
            if(bad.equals("expired"))h.b.utc=s.expiresAt();if(bad.equals("ambient"))h.b.active=true;if(bad.equals("revoked"))h.liveSession=false;
            denied(h,c,bad);
        }
        var ordinary=new OwnedFailedRenewalMethodServiceTest.Harness(true);ordinary.reserve();ordinary.b.trace.clear();denied(ordinary,ordinary.context,"non-browser");
    }
    @Test void originalDebtOperationBindingSequenceAndSampleMustStillBeExact(){
        for(var bad:List.of("stopped","authority","version","status","fingerprint","key-digest","method-authority","sequence","billing","mode","binding","debt","bill")){
            var h=unbound();drift(h,bad);denied(h,h.context,bad);
        }
    }
    @Test void bindingReinspectsExpectedContextAndRefusesEveryInterveningDriftBeforeWriting(){
        for(var bad:List.of("sequence","billing","mode","binding","version","debt","bill","proof","ack")){
            var h=unbound();var c=h.service.inspectBrowserBindingPreparation(h.reservation,h.context);
            if(bad.equals("proof")){var p=h.b.methodSetup;var s=step(h);var id=UUID.randomUUID();h.b.methodSetup=new Setup(p.operation(),p.subscription(),p.account(),id,p.reference(),null);h.providerSteps.put(s.operationKey(),new ProviderOperation(id,s.accountId(),s.externalAccountId(),s.credentialsRef(),s.kind(),s.resourceId(),s.operationKey(),s.fingerprint(),s.status(),s.providerReference(),s.firstDispatchedAt(),s.retryBefore(),null,null,2));}
            else if(bad.equals("ack")){var s=step(h);h.providerSteps.put(s.operationKey(),new ProviderOperation(s.id(),s.accountId(),s.externalAccountId(),s.credentialsRef(),s.kind(),s.resourceId(),s.operationKey(),s.fingerprint(),s.status(),s.providerReference(),s.firstDispatchedAt().plusSeconds(1),s.retryBefore().plusSeconds(1),null,null,2));h.b.utc=h.b.utc.plusSeconds(1);}
            else drift(h,bad);
            var before=state(h);h.b.trace.clear();assertThatThrownBy(()->h.service.bindBrowserMethod(c,h.context,"pm_confirmed")).as(bad).isInstanceOf(RuntimeException.class);
            assertThat(state(h)).isEqualTo(before);assertThat(h.b.trace).doesNotContain("binding-write");noEffects(h);
        }
    }
    @Test void malformedMethodAndAmbientTransactionCannotReachBindingWrite(){
        for(var method:Arrays.asList(null,"","pm_","not-a-method","pm_a/b","pm_"+"a".repeat(253))){
            var h=unbound();var c=h.service.inspectBrowserBindingPreparation(h.reservation,h.context);var before=state(h);h.b.trace.clear();
            assertThatThrownBy(()->h.service.bindBrowserMethod(c,h.context,method)).isInstanceOf(RuntimeException.class);
            assertThat(state(h)).isEqualTo(before);assertThat(h.b.trace).doesNotContain("binding-write");noEffects(h);
        }
        var h=unbound();var c=h.service.inspectBrowserBindingPreparation(h.reservation,h.context);h.b.active=true;h.b.trace.clear();
        assertThatThrownBy(()->h.service.bindBrowserMethod(c,h.context,"pm_confirmed")).isInstanceOf(RuntimeException.class);assertThat(h.b.trace).doesNotContain("begin","binding-write");
    }
    @Test void conditionalCompetitionCannotAdoptOrReplaceAnotherMethod(){
        var h=unbound();var c=h.service.inspectBrowserBindingPreparation(h.reservation,h.context);var before=state(h);h.competingBind=true;
        assertThatThrownBy(()->h.service.bindBrowserMethod(c,h.context,"pm_confirmed")).hasMessage("conditional binding unavailable");
        assertThat(state(h)).isEqualTo(before);assertThat(h.b.trace).contains("binding-write","rollback");noEffects(h);
    }
    @Test void finalInspectionWaitCannotExtendAuthorityOrEnrollment(){
        for(var bad:List.of("expiry","expiry-nanosecond","revoked","authority","enrollment")){
            var h=unbound();h.afterUnboundSetup=()->expireOrRevoke(h,bad);denied(h,h.context,bad);
        }
    }
    @Test void finalBindingWaitFailureRollsBackActualProofMutation(){
        for(var bad:List.of("expiry","expiry-nanosecond","revoked","authority","enrollment","write-failure")){
            var h=unbound();var c=h.service.inspectBrowserBindingPreparation(h.reservation,h.context);var before=state(h);
            h.afterBindingWrite=()->{assertThat(h.b.active).isTrue();assertThat(h.b.methodSetup.method()).isEqualTo("pm_confirmed");if(bad.equals("write-failure"))throw new IllegalStateException("after actual binding");expireOrRevoke(h,bad);};
            assertThatThrownBy(()->h.service.bindBrowserMethod(c,h.context,"pm_confirmed")).as(bad).isInstanceOf(RuntimeException.class);
            assertThat(state(h)).isEqualTo(before);assertThat(h.b.trace).contains("binding-write","rollback");noEffects(h);
        }
    }
    @Test void secondLiveSessionWithOriginalKeyCannotReplaceRevokedOriginalAtInspectionOrBinding(){
        var h=unbound();var c=h.service.inspectBrowserBindingPreparation(h.reservation,h.context);var s=session(h);
        h.secondLiveSession=new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),s.siteId(),s.customerId(),s.origin(),s.scopes(),s.expiresAt());
        var second=new SubscriptionCommandContext(new SubscriptionActor.Customer(h.secondLiveSession),s.siteId(),s.customerId(),s.origin(),h.context.idempotencyKey(),"second");h.liveSession=false;
        denied(h,second,"second live session");var before=state(h);
        assertThatThrownBy(()->h.service.bindBrowserMethod(c,second,"pm_confirmed")).isInstanceOf(RuntimeException.class);assertThat(state(h)).isEqualTo(before);noEffects(h);
    }
    @Test void descriptiveBindingContextRequiresEveryFieldAndRedactsIdentifiers(){
        var h=unbound();var c=h.service.inspectBrowserBindingPreparation(h.reservation,h.context);
        for(int i=0;i<3;i++){int n=i;assertThatThrownBy(()->new BindingPreparationContext(n==0?null:c.original(),n==1?null:c.setupProof(),n==2?null:c.setupStep())).isInstanceOf(IllegalArgumentException.class);}
        assertThat(c.toString()).isEqualTo("BindingPreparationContext[redacted]");
    }
    private static OwnedFailedRenewalMethodServiceTest.Harness unbound(){
        var h=new OwnedFailedRenewalMethodServiceTest.Harness(true,"alias",OwnedFailedRenewalServiceTest.UTC.plusSeconds(600).plusNanos(123456789));var r=h.service.reserveBrowserMethod(h.b.admission,h.context,true);var o=h.b.ops.get(r.setupOperationId());
        h.bindingEnabled=true;h.enforceSessionExpiry=true;h.providerEvidence=true;h.browserConsumed=true;var id=UUID.randomUUID();
        h.b.methodSetup=new Setup(o.id(),h.b.s.id(),h.b.account.id(),id,"seti_owned",null);
        var s=h.b.s;var binding=h.b.binding;
        var fingerprint=StripeRequestFingerprint.of(value->"digest:"+value,h.b.account,"SETUP_INTENT_CREATE_V1",o.id().toString(),o.providerOperationKey()+":SETUP",o.fingerprint(),Long.toString(o.policyVersion()),s.id().toString(),s.siteId().value().toString(),s.customerId().value().toString(),binding.providerReference(),"off_session","card","consent:true");
        var step=new ProviderOperation(id,h.b.account.id(),h.b.account.externalAccountId(),h.b.account.credentialsRef(),ProviderOperation.Kind.SETUP_INTENT_CREATE,o.id(),o.providerOperationKey()+":SETUP",fingerprint,ProviderOperation.Status.ACKNOWLEDGED,"seti_owned",h.b.utc,h.b.utc.plusSeconds(23*3600),null,null,2);
        h.providerSteps.put(step.operationKey(),step);h.requireBoundEvidence=()->{if(!fingerprint.equals(step(h).fingerprint()))throw new IllegalStateException("noncanonical fingerprint");};h.b.trace.clear();return h;
    }
    private static void expireOrRevoke(OwnedFailedRenewalMethodServiceTest.Harness h,String bad){
        // One nanosecond after exact expiry must fail even if service UTC truncation precedes it.
        if(bad.equals("expiry"))h.b.utc=session(h).expiresAt();if(bad.equals("expiry-nanosecond"))h.b.utc=session(h).expiresAt().plusNanos(1);if(bad.equals("revoked"))h.liveSession=false;if(bad.equals("authority"))h.authorized=false;
        if(bad.equals("enrollment")){var e=h.enrollment;h.enrollment=new OwnedMethodBrowser.Enrollment(UUID.randomUUID(),e.origin(),e.sessionExpiresAt());}
    }
    private static void drift(OwnedFailedRenewalMethodServiceTest.Harness h,String bad){
        var o=h.b.ops.get(h.reservation.setupOperationId());if(bad.equals("stopped"))h.b.stopped=true;if(bad.equals("authority"))h.authorized=false;
        if(Set.of("version","status","fingerprint","key-digest","method-authority").contains(bad))h.b.ops.put(o.id(),new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),bad.equals("status")?SubscriptionOperation.Status.PROCESSING:o.status(),bad.equals("method-authority")?"foreign":o.authorityScope(),bad.equals("key-digest")?"foreign":o.keyDigest(),bad.equals("fingerprint")?"foreign":o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),o.updatedAt(),bad.equals("version")?1:o.version()));
        if(bad.equals("sequence"))h.b.sequence++;if(bad.equals("billing"))h.b.billingSecond++;if(bad.equals("mode"))h.b.isolated=false;
        if(bad.equals("binding")){var b=h.b.binding;h.b.binding=new CustomerBinding(b.id(),b.accountId(),b.siteId(),b.customerId(),b.providerReference(),b.creationOperationId(),b.version()+1);}
        if(bad.equals("debt")){var s=h.b.s;h.b.s=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),null,s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
        if(bad.equals("bill")){var b=h.b.records.get("in_renewal");h.b.records.put("in_renewal",new BillingRecord(b.id(),b.siteId(),b.subscriptionId(),b.accountId(),b.providerInvoiceReference(),b.kind(),b.billedPrice(),b.previousPrice(),b.period(),b.amountDueMinor(),b.currency(),b.settlement(),b.version()+1,b.createdAt(),b.updatedAt()));}
    }
    private static ProviderOperation changedStep(ProviderOperation s,String bad){
        var first=bad.equals("first")?null:bad.equals("before-reservation")?s.firstDispatchedAt().minusSeconds(1):bad.equals("future")?s.firstDispatchedAt().plusSeconds(1):s.firstDispatchedAt();
        return new ProviderOperation(bad.equals("id")?UUID.randomUUID():s.id(),bad.equals("account")?new PaymentAccountId(UUID.randomUUID()):s.accountId(),bad.equals("external-account")?"acct_foreign":s.externalAccountId(),bad.equals("credential")?"foreign":s.credentialsRef(),bad.equals("kind")?ProviderOperation.Kind.SUBSCRIPTION_UPDATE:s.kind(),bad.equals("resource")?UUID.randomUUID():s.resourceId(),bad.equals("key")?"foreign:SETUP":s.operationKey(),s.fingerprint(),bad.equals("status")?ProviderOperation.Status.DISPATCHING:s.status(),bad.equals("reference")?"seti_foreign":s.providerReference(),first,bad.equals("window")?s.retryBefore().plusSeconds(1):s.retryBefore(),bad.equals("lease-owner")?"foreign":s.leaseOwner(),bad.equals("lease-expiry")?s.firstDispatchedAt().plusSeconds(60):s.leaseExpiresAt(),bad.equals("version")?3:s.version());
    }
    private static ProviderOperation step(OwnedFailedRenewalMethodServiceTest.Harness h){return h.providerSteps.get(h.b.ops.get(h.reservation.setupOperationId()).providerOperationKey()+":SETUP");}
    private static CustomerSessionContext session(OwnedFailedRenewalMethodServiceTest.Harness h){return ((SubscriptionActor.Customer)h.context.actor()).session();}
    private static void denied(OwnedFailedRenewalMethodServiceTest.Harness h,SubscriptionCommandContext c,String label){var before=state(h);assertThatThrownBy(()->h.service.inspectBrowserBindingPreparation(h.reservation,c)).as(label).isInstanceOf(RuntimeException.class);assertThat(state(h)).as(label).isEqualTo(before);noEffects(h);}
    private static void noEffects(OwnedFailedRenewalMethodServiceTest.Harness h){assertThat(h.b.trace).doesNotContain("stop","prepared","claim","facts","completion","publication");}
    private static List<Object> business(OwnedFailedRenewalMethodServiceTest.Harness h){return Arrays.asList(h.b.s,new LinkedHashMap<>(h.b.ops),new LinkedHashMap<>(h.b.records),new LinkedHashSet<>(h.b.cash),new ArrayList<>(h.b.events),h.b.callbacks,h.b.sequence,h.b.effects,h.b.stopped,h.reservation,h.enrollment,h.completion,h.recovery,h.publication,new HashMap<>(h.claims),new LinkedHashMap<>(h.providerSteps));}
    private static List<Object> state(OwnedFailedRenewalMethodServiceTest.Harness h){return Arrays.asList(business(h),h.b.methodSetup);}
}
