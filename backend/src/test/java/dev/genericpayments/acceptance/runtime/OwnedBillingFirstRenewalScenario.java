package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonObject;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.service.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/** Fixed single-use composition. All provider and financial authority stays in existing durable consumers. */
public final class OwnedBillingFirstRenewalScenario implements OwnedBillingFirstRenewalRuntime.Operations {
    private final OwnedBillingFirstPaymentScenario initial;
    private final OwnedBillingFirstPaymentRuntime.Admission owner;
    private final Function<Class<?>,Object> beans;
    private final Clock utc;private final Instant deadline;
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile String phase="INITIAL";
    private volatile String boundaryFailureStage="UNCLASSIFIED";
    private Admission admission;private Boundary boundary;
    public OwnedBillingFirstRenewalScenario(OwnedBillingFirstPaymentRuntime.Admission owner,Function<Class<?>,Object> beans,Clock utc,Instant deadline) {
        this.owner=Objects.requireNonNull(owner);this.beans=Objects.requireNonNull(beans);this.utc=Objects.requireNonNull(utc);this.deadline=Objects.requireNonNull(deadline);
        initial=new OwnedBillingFirstPaymentScenario(owner,beans,utc,deadline);
    }
    private <T>T bean(Class<T> type){return type.cast(beans.apply(type));}
    private void check(){if(phase.equals("STOPPED")||!utc.instant().isBefore(deadline))throw stopped();}
    private synchronized void begin(String expected,String next){check();if(!phase.equals(expected))throw stopped();phase=next;}
    private JsonObject invoke(Supplier<JsonObject> work) {
        if(!busy.compareAndSet(false,true)){abort();throw stopped();}
        try{check();return work.get();}catch(RuntimeException|Error failure){abort();throw stopped();}finally{busy.set(false);}
    }
    private JsonObject initial(Supplier<JsonObject> work){return invoke(()->{if(!phase.equals("INITIAL"))throw stopped();var result=work.get();check();return result;});}
    @Override public JsonObject prepare(JsonObject request){return initial(()->initial.prepare(request));}
    @Override public String prepareFailureStage(){return initial.prepareFailureStage();}
    @Override public JsonObject confirm(JsonObject request){return initial(()->initial.confirm(request));}
    @Override public JsonObject observe(){return initial(initial::observe);}
    @Override public JsonObject status(){if(!utc.instant().isBefore(deadline))abort();var response=initial.status();if(!phase.equals("INITIAL")){response.addProperty("phase",phase);response.addProperty("code",phase.equals("STOPPED")?"FIRST_RENEWAL_STOPPED":"OK");}return response;}
    @Override public String boundaryFailureStage(){return boundaryFailureStage;}
    @Override public JsonObject boundary(){return invoke(()->{
        begin("INITIAL","BOUNDARY_RUNNING");
        boundaryFailureStage="INITIAL_RECEIPT";
        var receipt=initial.settledReceipt();initial.authenticatedView();var scope=receipt.intent().clock().owner();
        require(scope.run().equals(owner.run())&&scope.initialFrozenTime()==owner.frozen());
        var terms=receipt.intent().terms();require(terms.regularAmount().minorUnits()==2000&&terms.regularAmount().currency().equals("EUR")&&terms.interval()==dev.genericpayments.platform.catalog.domain.BillingInterval.MONTH&&terms.trialDays()==0&&terms.taxMode()==dev.genericpayments.platform.catalog.domain.TaxMode.INCLUSIVE);
        boundaryFailureStage="ADMISSION";
        check();admission=new OwnedFirstRenewalAdmissionService(bean(OwnedFirstRenewalStore.class),bean(SubscriptionTransactionPort.class),bean(SubscriptionOperationStore.class),bean(SubscriptionLifecycleLockPort.class),bean(LockPaymentAccountPort.class),bean(SubscriptionStore.class),utc).admit(receipt,owner.source());
        require(admission.receipt().equals(receipt)&&admission.sourceSha().equals(owner.source()));
        boundaryFailureStage="CLOCK_ADVANCE";
        check();var ready=advance().advance(admission,Step.BOUNDARY);
        boundaryFailureStage="GRAPH_OBSERVER_INIT";
        check();var observation=observer().observe(admission,ready);
        boundaryFailureStage="GRAPH_ASSERT";
        require(observation instanceof Pending);var pending=(Pending)observation;boundary=pending.boundary();
        require(pending.admission().equals(admission)&&pending.ready().equals(ready)&&boundary.equals(bean(OwnedFirstRenewalStore.class).boundary(admission))
            &&boundary.period().start().equals(Instant.ofEpochSecond(receipt.periodEnd()))&&boundary.period().start().plusSeconds(3600).isBefore(boundary.period().end()));
        boundaryFailureStage="BOUNDARY_RESPONSE";
        begin("BOUNDARY_RUNNING","RENEWAL_BOUNDARY");var response=status();response.addProperty("renewal_admission_id",admission.id().toString());response.addProperty("boundary_time",boundary.period().start().toString());response.addProperty("period_start",boundary.period().start().toString());response.addProperty("period_end",boundary.period().end().toString());return response;
    });}
    @Override public JsonObject finalization(){return invoke(()->{
        begin("RENEWAL_BOUNDARY","FINALIZATION_RUNNING");initial.authenticatedView();require(boundary.equals(bean(OwnedFirstRenewalStore.class).boundary(admission)));
        check();var ready=advance().advance(admission,Step.FINALIZATION);
        check();var observation=observer().observe(admission,ready);require(observation instanceof Paid);var paid=(Paid)observation;
        require(paid.admission().equals(admission)&&paid.ready().equals(ready)&&paid.boundary().equals(boundary));
        check();var publication=new OwnedFirstRenewalPublicationService(bean(OwnedFirstRenewalStore.class),bean(SubscriptionTransactionPort.class),bean(SubscriptionOperationStore.class),bean(SubscriptionLifecycleLockPort.class),bean(LockPaymentAccountPort.class),bean(SubscriptionStore.class),bean(PrepareSubscriptionReadUseCase.class),bean(SubscriptionObservationPort.class),bean(ApplySubscriptionSnapshotUseCase.class),bean(TrialEligibilityStore.class),utc).publish(admission,ready,paid);
        check();var view=initial.authenticatedView();var subscription=view.subscription();var access=view.access();var evaluated=boundary.period().start().plusSeconds(3600);
        require(publication.admissionId().equals(admission.id())&&publication.boundary().equals(boundary)&&publication.ready().equals(ready)
            &&subscription.billingStatus()==Subscription.Status.ACTIVE&&subscription.billingPrice().equals(admission.receipt().intent().terms())&&subscription.entitledPrice().equals(subscription.billingPrice())
            &&boundary.period().equals(subscription.currentPeriod())&&boundary.period().end().equals(subscription.paidThrough())&&access.allowed()&&access.reason()==AccessDecision.Reason.PAID
            &&access.evaluatedAt().equals(evaluated)&&access.validUntil().equals(evaluated.plusSeconds(300)));
        begin("FINALIZATION_RUNNING","RENEWAL_SETTLED_LOCAL");var response=status();response.addProperty("renewal_admission_id",admission.id().toString());
        // V33 keys the single committed publication by admission_id; no invented receipt identifier.
        response.addProperty("publication_id",publication.admissionId().toString());response.addProperty("period_start",boundary.period().start().toString());response.addProperty("paid_through",subscription.paidThrough().toString());response.addProperty("access_evaluated_at",access.evaluatedAt().toString());response.addProperty("access_valid_until",access.validUntil().toString());return response;
    });}
    private StripeBillingFirstRenewalAdvanceAdapter advance(){return new StripeBillingFirstRenewalAdvanceAdapter(bean(StripeCredentialResolver.class),bean(StripeClientFactory.class),bean(LoadPaymentAccountPort.class),bean(ProviderOperationStore.class),bean(OwnedFirstRenewalStore.class),utc,stage->boundaryFailureStage=stage);}
    private StripeBillingFirstRenewalAdapter observer(){return new StripeBillingFirstRenewalAdapter(bean(StripeCredentialResolver.class),bean(StripeClientFactory.class),bean(OwnedFirstRenewalStore.class),bean(SubscriptionTransactionPort.class),bean(SubscriptionOperationStore.class),bean(SubscriptionLifecycleLockPort.class),bean(LockPaymentAccountPort.class),bean(SubscriptionStore.class),bean(PrepareSubscriptionReadUseCase.class),bean(SubscriptionObservationPort.class),bean(StripeBillingBindingPort.class),utc,stage->boundaryFailureStage=stage);}
    @Override public synchronized void abort(){if(phase.equals("STOPPED"))return;phase="STOPPED";if(admission!=null)try{bean(OwnedFirstRenewalStore.class).stop(admission,"AUTHORITY_DRIFT",utc.instant());}catch(RuntimeException ignored){}initial.abort();}
    private static void require(boolean valid){if(!valid)throw stopped();}
    private static IllegalStateException stopped(){return new IllegalStateException("FIRST_RENEWAL_STOPPED");}
}
