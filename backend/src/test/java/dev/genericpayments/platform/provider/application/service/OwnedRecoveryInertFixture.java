package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.adapter.out.stripe.StripeRequestFingerprint;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.time.*;
import java.util.*;
import static dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalServiceTest.*;

/** Package bridge to the existing pure memory harness. All lifecycle transitions are actual service calls.
 * Only the external acknowledged provider records are handwritten; no provider or database is reachable. */
public final class OwnedRecoveryInertFixture {
    final OwnedFailedRenewalMethodServiceTest.Harness h;
    final DigestPort digest;
    public final List<Object> proofLocators=new ArrayList<>();
    public final List<UUID> stepLocators=new ArrayList<>();
    public SubscriptionBillingProofStore.Setup proof;
    public final Map<UUID,ProviderOperation> steps=new LinkedHashMap<>();
    public Runnable afterProof=()->{};
    public final SubscriptionBillingProofStore proofs=port(SubscriptionBillingProofStore.class,(method,args)->{
        if(!method.equals("setup"))throw new AssertionError("Unexpected proof mutation/read: "+method);
        proofLocators.add(List.of(args[0],args[1]));var result=proof;afterProof.run();return Optional.ofNullable(result);
    });
    public final ProviderOperationStore providerSteps=port(ProviderOperationStore.class,(method,args)->{
        if(!method.equals("findById"))throw new AssertionError("Unexpected provider mutation/read: "+method);
        var id=(UUID)args[0];stepLocators.add(id);return Optional.ofNullable(steps.get(id));
    });
    public OwnedRecoveryInertFixture(DigestPort digest){
        this.digest=digest;
        h=new OwnedFailedRenewalMethodServiceTest.Harness(true,"stripe-sandbox-default",UTC.plusSeconds(600).plusNanos(123456789),digest);
        var r=h.service.reserveBrowserMethod(h.b.admission,h.context,true);
        h.acks=true;h.browserConsumed=true;h.recoveryPreparationEnabled=true;h.enforceSessionExpiry=true;
        proof=new SubscriptionBillingProofStore.Setup(r.setupOperationId(),h.b.s.id(),h.b.account.id(),id(91),"seti_owned","pm_owned");
        h.b.methodSetup=proof;var o=h.b.ops.get(r.setupOperationId());var s=h.b.s;var a=h.b.account;
        String sf=StripeRequestFingerprint.of(digest,a,"SETUP_INTENT_CREATE_V1",o.id().toString(),o.providerOperationKey()+":SETUP",o.fingerprint(),"1",
            s.id().toString(),s.siteId().value().toString(),s.customerId().value().toString(),"cus_owned","off_session","card","consent:true");
        String mf=StripeRequestFingerprint.of(digest,a,"SUBSCRIPTION_METHOD_V1",o.id().toString(),o.providerOperationKey()+":METHOD",o.fingerprint(),
            "sub_owned","cus_owned",proof.step().toString(),"seti_owned","pm_owned","consent:true");
        var setup=h.step(o,false);var method=h.step(o,true);
        setup=fingerprint(setup,sf);method=fingerprint(method,mf);
        h.providerSteps.put(setup.operationKey(),setup);h.providerSteps.put(method.operationKey(),method);h.providerEvidence=true;
        steps.put(setup.id(),setup);steps.put(method.id(),method);
        h.service.recordMethodUpdateReply(r,h.context);var q=h.service.beginMethodRead(r,UUID.randomUUID());
        var completion=h.service.publishMethod(r,q,h.evidence(),h.service.inspectMethodRead(r,q));
        var recovery=h.service.reserveRecovery(r,completion);h.b.recovery=recovery.association();h.b.trace.clear();
    }
    private static ProviderOperation fingerprint(ProviderOperation s,String fp){return new ProviderOperation(s.id(),s.accountId(),s.externalAccountId(),s.credentialsRef(),s.kind(),s.resourceId(),s.operationKey(),fp,s.status(),s.providerReference(),s.firstDispatchedAt(),s.retryBefore(),s.leaseOwner(),s.leaseExpiresAt(),s.version());}
    public OwnedFailedRenewalMethodService service(){return h.service;}
    public Reservation reservation(){return h.reservation;}
    public Completion completion(){return h.completion;}
    public Recovery recovery(){return h.recovery;}
    public SubscriptionCommandContext command(){return h.context;}
    public RecoveryPreparationContext inspect(){return h.service.inspectBrowserRecoveryPreparation(reservation(),completion(),recovery(),command());}
    public Clock clock(){return h.b.clockPort;}
    /** Actual inert caller boundary; a confirmation fixture may enlist its own rollback state. */
    public SubscriptionTransactionPort transactions(){return h.b.transactionPort;}
    public Instant now(){return h.b.utc;}
    public void at(Instant at){h.b.utc=at;}
    public void revoke(){h.authorized=false;}
    public void afterAuthentication(Runnable action){h.afterRecoveryEnrollment=action;}
    public void driftSequence(){h.b.sequence++;}
    public List<String> trace(){return h.b.trace;}
    public Object state(){return Arrays.asList(h.b.s,new LinkedHashMap<>(h.b.ops),new LinkedHashMap<>(h.b.records),new LinkedHashSet<>(h.b.cash),new ArrayList<>(h.b.events),h.b.callbacks,h.b.sequence,h.b.effects,h.b.stopped,h.reservation,h.enrollment,h.completion,h.recovery,h.publication,new HashMap<>(h.claims),h.b.methodSetup,new LinkedHashMap<>(h.providerSteps));}
}
