package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual service and explicit inert caller transactions; no runtime, SQL or provider calls. */
class OwnedMethodApplicationPreparationTest {
    @Test void boundOriginalSetupIsInspectableBeforeMethodWithoutChangingAnyState() {
        var h=bound();var r=h.reservation;var before=state(h);h.b.trace.clear();
        var c=h.service.inspectBrowserMethodPreparation(r,h.context);
        assertThat(c.original().reservation()).isEqualTo(r);assertThat(c.original().enrollment()).isEqualTo(h.enrollment);
        assertThat(c.original().account()).isEqualTo(h.b.account);assertThat(c.original().subscription()).isEqualTo(h.b.s);
        assertThat(c.original().operation()).isEqualTo(h.b.ops.get(r.setupOperationId()));assertThat(c.original().binding()).isEqualTo(r.binding());
        assertThat(c.original().billingSample()).isEqualTo(r.billingSample());assertThat(c.setupProof()).isEqualTo(h.b.methodSetup);
        assertThat(c.setupStep()).isEqualTo(setupStep(h));assertThat(h.providerSteps).hasSize(1);
        assertThat(state(h)).isEqualTo(before);assertThat(h.b.trace).containsSubsequence("begin","setup-enrollment","bound-setup","authority","commit");
        assertThat(h.b.trace.stream().filter("begin"::equals)).hasSize(1);
        assertThat(h.b.trace).doesNotContain("stop","prepared","claim","reservation","facts","completion");
    }
    @Test void ownFirstDispatchingMethodClaimDoesNotMakeBoundSetupInspectionCircular() {
        var h=bound();var s=setupStep(h);var o=h.b.ops.get(h.reservation.setupOperationId());
        var method=new ProviderOperation(UUID.randomUUID(),s.accountId(),s.externalAccountId(),s.credentialsRef(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE,o.id(),o.providerOperationKey()+":METHOD","method-fingerprint",ProviderOperation.Status.DISPATCHING,null,h.b.utc,h.b.utc.plusSeconds(23*3600),"own-claim",h.b.utc.plusSeconds(60),1);
        h.providerSteps.put(method.operationKey(),method);var before=state(h);
        var c=h.service.inspectBrowserMethodPreparation(h.reservation,h.context);
        assertThat(c.setupStep()).isEqualTo(s);assertThat(state(h)).isEqualTo(before);
        assertThat(h.providerSteps.get(method.operationKey())).isEqualTo(method);
    }
    @Test void boundSetupFixtureCannotAdvanceTheLifecycleWithoutAnActualMethodAck() {
        var h=bound();var original=h.b.ops.get(h.reservation.setupOperationId());
        assertThat(h.acks).isFalse();assertThat(h.providerSteps).hasSize(1);
        assertThatThrownBy(()->h.service.recordMethodUpdateReply(h.reservation,h.context)).hasMessage("not prepared");
        assertThat(h.b.ops.get(original.id())).isEqualTo(original);assertThat(original.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
        assertThat(h.completion).isNull();assertThat(h.providerSteps).hasSize(1);
    }
    @Test void boundInspectionHasNoFallbackForMissingUnboundOrUncommittedOriginalEvidence() {
        for(var bad:List.of("consumption","uncommitted-consumption","proof","unbound","ack","fingerprint")) {
            var h=bound();
            if(bad.equals("consumption")||bad.equals("uncommitted-consumption"))h.browserConsumed=false;
            if(bad.equals("proof"))h.b.methodSetup=null;
            if(bad.equals("unbound")){var p=h.b.methodSetup;h.b.methodSetup=new Setup(p.operation(),p.subscription(),p.account(),p.step(),p.reference(),null);}
            if(bad.equals("ack"))h.providerSteps.clear();
            // Canonical fingerprint and committed-consumption validation belong to the JDBC store.
            if(bad.equals("fingerprint"))h.boundSetupDenied=true;
            denied(h,h.context,bad);
        }
    }
    @Test void returnedBoundProofAndEnrollmentMustMatchTheOriginalEvenWhenThePortReturnsThem() {
        for(var bad:List.of("enrollment","operation","subscription","account","step","reference","method","unbound")) {
            var h=bound();var p=h.b.methodSetup;var e=h.enrollment;
            if(bad.equals("enrollment"))e=new OwnedMethodBrowser.Enrollment(UUID.randomUUID(),e.origin(),e.sessionExpiresAt());
            var forged=new Setup(bad.equals("operation")?UUID.randomUUID():p.operation(),bad.equals("subscription")?UUID.randomUUID():p.subscription(),
                bad.equals("account")?new PaymentAccountId(UUID.randomUUID()):p.account(),bad.equals("step")?UUID.randomUUID():p.step(),
                bad.equals("reference")?"seti_foreign":p.reference(),bad.equals("method")?"not-a-method":bad.equals("unbound")?null:p.method());
            h.returnedBoundSetup=new BoundSetup(e,forged,setupStep(h));denied(h,h.context,bad);
        }
    }
    @Test void originalAckRequiresExactIdentityVersionFirstWindowAndClearedLease() {
        for(var bad:List.of("id","account","external-account","credential","kind","resource","key","status","reference","version","first","window","lease-owner","lease-expiry")) {
            var h=bound();h.returnedBoundSetup=new BoundSetup(h.enrollment,h.b.methodSetup,changedStep(setupStep(h),bad));denied(h,h.context,bad);
        }
    }
    @Test void originalClientAuthorityAndKeyCannotBeReplacedOrExpiredAndNeverStopTheHolder() {
        for(var bad:List.of("session","origin","customer","site","expiry","expiry-nanosecond","scope","admin","key","missing-key","expired","ambient","revoked")) {
            var h=bound();var s=session(h);var c=h.context;
            if(Set.of("session","origin","customer","site","expiry","expiry-nanosecond","scope").contains(bad)) {
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
        var ordinary=new OwnedFailedRenewalMethodServiceTest.Harness(true);ordinary.reserve();denied(ordinary,ordinary.context,"non-browser");
    }
    @Test void latestBoundStoreWaitCannotExtendTheOriginalSessionOrManagementAuthority() {
        for(var bad:List.of("expired","revoked","authority")) {
            var h=bound();h.afterBoundSetup=()->{if(bad.equals("expired"))h.b.utc=session(h).expiresAt();if(bad.equals("revoked"))h.liveSession=false;if(bad.equals("authority"))h.authorized=false;};
            denied(h,h.context,bad);
        }
    }
    @Test void locksCannotExtendOriginalAuthorityBeforeBoundStoreWork() {
        for(var bad:List.of("expired","revoked","authority")) {
            var h=bound();h.afterAccountLock=()->{if(bad.equals("expired"))h.b.utc=session(h).expiresAt();if(bad.equals("revoked"))h.liveSession=false;if(bad.equals("authority"))h.authorized=false;};
            denied(h,h.context,bad);assertThat(h.b.trace).doesNotContain("bound-setup");
        }
    }
    @Test void originalDebtOperationBindingSequenceAndIsolatedTimeMustRemainExact() {
        for(var bad:List.of("stopped","authority","version","status","fingerprint","key-digest","method-authority","sequence","billing","mode","binding","debt","bill")) {
            var h=bound();var o=h.b.ops.get(h.reservation.setupOperationId());
            if(bad.equals("stopped"))h.b.stopped=true;if(bad.equals("authority"))h.authorized=false;
            if(Set.of("version","status","fingerprint","key-digest","method-authority").contains(bad))h.b.ops.put(o.id(),new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),
                bad.equals("status")?SubscriptionOperation.Status.PROCESSING:o.status(),bad.equals("method-authority")?"foreign":o.authorityScope(),bad.equals("key-digest")?"foreign":o.keyDigest(),bad.equals("fingerprint")?"foreign":o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),o.updatedAt(),bad.equals("version")?1:o.version()));
            if(bad.equals("sequence"))h.b.sequence++;if(bad.equals("billing"))h.b.billingSecond++;if(bad.equals("mode"))h.b.isolated=false;
            if(bad.equals("binding")){var b=h.b.binding;h.b.binding=new CustomerBinding(b.id(),b.accountId(),b.siteId(),b.customerId(),b.providerReference(),b.creationOperationId(),b.version()+1);}
            if(bad.equals("debt")){var s=h.b.s;h.b.s=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),null,s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
            if(bad.equals("bill")){var b=h.b.records.get("in_renewal");h.b.records.put("in_renewal",new BillingRecord(b.id(),b.siteId(),b.subscriptionId(),b.accountId(),b.providerInvoiceReference(),b.kind(),b.billedPrice(),b.previousPrice(),b.period(),b.amountDueMinor(),b.currency(),b.settlement(),b.version()+1,b.createdAt(),b.updatedAt()));}
            denied(h,h.context,bad);
        }
    }
    @Test void descriptiveBoundModelsRequireAllFieldsAndRedactOriginalIdentifiers() {
        var h=bound();var p=h.b.methodSetup;var step=setupStep(h);var original=h.service.inspectBrowserSetupPreparation(h.reservation,h.context);
        for(int i=0;i<3;i++){int n=i;
            assertThatThrownBy(()->new BoundSetup(n==0?null:h.enrollment,n==1?null:p,n==2?null:step)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->new MethodPreparationContext(n==0?null:original,n==1?null:p,n==2?null:step)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new BoundSetup(h.enrollment,p,step).toString()).isEqualTo("BoundSetup[redacted]");
        assertThat(new MethodPreparationContext(original,p,step).toString()).isEqualTo("MethodPreparationContext[redacted]");
    }
    private static OwnedFailedRenewalMethodServiceTest.Harness bound(){
        var h=new OwnedFailedRenewalMethodServiceTest.Harness(true);var r=h.service.reserveBrowserMethod(h.b.admission,h.context,true);var o=h.b.ops.get(r.setupOperationId());
        h.providerEvidence=true;h.browserConsumed=true;var id=UUID.randomUUID();h.b.methodSetup=new Setup(o.id(),h.b.s.id(),h.b.account.id(),id,"seti_owned","pm_owned");
        var step=new ProviderOperation(id,h.b.account.id(),h.b.account.externalAccountId(),h.b.account.credentialsRef(),ProviderOperation.Kind.SETUP_INTENT_CREATE,o.id(),o.providerOperationKey()+":SETUP","fingerprint",ProviderOperation.Status.ACKNOWLEDGED,"seti_owned",h.b.utc,h.b.utc.plusSeconds(23*3600),null,null,2);
        h.providerSteps.put(step.operationKey(),step);h.b.trace.clear();return h;
    }
    private static ProviderOperation setupStep(OwnedFailedRenewalMethodServiceTest.Harness h){return h.providerSteps.get(h.b.ops.get(h.reservation.setupOperationId()).providerOperationKey()+":SETUP");}
    private static CustomerSessionContext session(OwnedFailedRenewalMethodServiceTest.Harness h){return ((SubscriptionActor.Customer)h.context.actor()).session();}
    private static void denied(OwnedFailedRenewalMethodServiceTest.Harness h,SubscriptionCommandContext c,String label){
        var before=state(h);assertThatThrownBy(()->h.service.inspectBrowserMethodPreparation(h.reservation,c)).as(label).isInstanceOf(RuntimeException.class);
        assertThat(state(h)).as(label).isEqualTo(before);assertThat(h.b.trace).doesNotContain("stop","facts","completion");
    }
    private static List<Object> state(OwnedFailedRenewalMethodServiceTest.Harness h){
        return Arrays.asList(h.b.s,new LinkedHashMap<>(h.b.ops),new LinkedHashMap<>(h.b.records),new LinkedHashSet<>(h.b.cash),new ArrayList<>(h.b.events),h.b.callbacks,h.b.sequence,h.b.effects,h.b.stopped,h.reservation,h.enrollment,h.completion,h.recovery,h.publication,new HashMap<>(h.claims),h.b.methodSetup,new LinkedHashMap<>(h.providerSteps));
    }
    private static ProviderOperation changedStep(ProviderOperation s,String bad){
        return new ProviderOperation(bad.equals("id")?UUID.randomUUID():s.id(),bad.equals("account")?new PaymentAccountId(UUID.randomUUID()):s.accountId(),bad.equals("external-account")?"acct_foreign":s.externalAccountId(),bad.equals("credential")?"foreign":s.credentialsRef(),
            bad.equals("kind")?ProviderOperation.Kind.SUBSCRIPTION_UPDATE:s.kind(),bad.equals("resource")?UUID.randomUUID():s.resourceId(),bad.equals("key")?"foreign:SETUP":s.operationKey(),s.fingerprint(),bad.equals("status")?ProviderOperation.Status.DISPATCHING:s.status(),bad.equals("reference")?"seti_foreign":s.providerReference(),
            bad.equals("first")?null:s.firstDispatchedAt(),bad.equals("window")?s.retryBefore().plusSeconds(1):s.retryBefore(),bad.equals("lease-owner")?"foreign":s.leaseOwner(),bad.equals("lease-expiry")?s.firstDispatchedAt().plusSeconds(60):s.leaseExpiresAt(),bad.equals("version")?3:s.version());
    }
}
