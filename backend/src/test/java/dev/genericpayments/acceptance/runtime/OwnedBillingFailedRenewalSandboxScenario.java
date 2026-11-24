package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedGraceAdvanceAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalMethodObserver.RecoveryDiagnostic;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalObserver;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.service.OwnedMethodBrowserService;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.*;

/** Fixed actual producer chain. Descriptive JSON never supplies authority to the next producer. */
public final class OwnedBillingFailedRenewalSandboxScenario implements OwnedBillingFailedRenewalOperations {
    private enum Stage { UNCLASSIFIED, INITIAL, BOUNDARY, DECLINE_ARM, FAILED_RENEWAL, GRACE_LAST_SECOND, GRACE_AT, GRACE_AFTER,
        METHOD_PREPARE, METHOD_SELECT, METHOD_CONFIRM, METHOD_APPLY, RECOVERY_CONFIRM, RECOVERY_OBSERVE }
    private final OwnedBillingFirstPaymentScenario initial;private final OwnedBillingFirstPaymentRuntime.Admission owner;
    private final Function<Class<?>,Object> beans;private final Clock utc;private final Instant deadline;private final State state;
    private volatile Stage failureStage=Stage.UNCLASSIFIED;private OwnedBillingFailedRenewalComponents components;
    private String detailedStep;
    private volatile RuntimeException detailedRefusal;
    private OwnedFirstRenewal.Admission upstream;private OwnedFirstRenewal.Pending pending;
    private FailureArmEvidence armEvidence;
    private OwnedFailedRenewal.Admission failed;private OwnedFailedRenewal.Failure failure;
    private OwnedFailedRenewal.GraceLastSecondPreparation preparation;private OwnedFailedGraceAdvance.GraceAdvanceReady graceReady;
    private OwnedFailedGraceBoundary.BoundaryReady at,after;
    private OwnedFailedRenewalMethod.Reservation reservation;private OwnedMethodBrowserService browser;
    private SubscriptionProviderModels.NextAction action;private SubscriptionConfirmationUseCase.Session selected;
    private OwnedFailedRenewalMethod.Completion completion;private OwnedFailedRenewalMethod.Recovery reservedRecovery;
    private OwnedBillingFailedRenewalRecovery recovery;private boolean cleaned;

    public OwnedBillingFailedRenewalSandboxScenario(OwnedBillingFirstPaymentRuntime.Admission owner,Function<Class<?>,Object> beans,Clock utc,Instant deadline){
        this.owner=Objects.requireNonNull(owner);this.beans=Objects.requireNonNull(beans);this.utc=Objects.requireNonNull(utc);this.deadline=Objects.requireNonNull(deadline);
        require(utc.getZone().equals(ZoneOffset.UTC));state=new State(utc,deadline);initial=new OwnedBillingFirstPaymentScenario(owner,beans,utc,deadline);
    }
    private <T>T bean(Class<T> type){return type.cast(beans.apply(type));}
    private OwnedBillingFailedRenewalComponents parts(){if(components==null)components=new OwnedBillingFailedRenewalComponents(beans,utc);return components;}
    private String managementKey(){return "failed-renewal-management-"+owner.run();}
    private SubscriptionCommandContext management(){state.checkActive();return initial.authenticatedContext(managementKey());}
    private SubscriptionCommandContext browserContext(String purpose){state.checkActive();return initial.authenticatedContext("failed-renewal-browser-"+purpose+"-"+owner.run());}
    private JsonObject stage(String expected,String next,Stage checkpoint,Supplier<JsonObject> work){
        final long ticket;
        try{ticket=state.enter(expected,next+"_RUNNING");}catch(RuntimeException invalid){if(state.stoppedIdle())cleanup();throw denied();}
        try{failureStage=checkpoint;detailedStep=null;var result=work.get();state.complete(ticket,next+"_RUNNING",next);return result;}
        catch(RuntimeException invalid){
            if(state.fail(ticket))cleanup();
            var refusal=denied();
            if(detailedStep!=null)detailedRefusal=refusal;
            throw refusal;
        }
        catch(Error invalid){if(state.fail(ticket))cleanup();throw invalid;}
    }
    private JsonObject initial(Supplier<JsonObject> work){return stage("INITIAL","INITIAL",Stage.INITIAL,work);}
    @Override public JsonObject prepare(JsonObject request){return initial(()->initial.prepare(request));}
    @Override public JsonObject confirm(JsonObject request){return initial(()->{
        try{return initial.confirm(request);}
        catch(RuntimeException refusal){detailedStep=initial.confirmFailureStage(refusal);throw refusal;}
    });}
    @Override public String confirmFailureStage(Exception refusal){
        return refusal!=null&&refusal==detailedRefusal?detailedStep:"CONFIRM_UNCLASSIFIED";
    }
    @Override public JsonObject observe(){return initial(initial::observe);}
    @Override public String prepareFailureStage(){return initial.prepareFailureStage();}
    @Override public String failureStage(){return failureStage.name();}
    @Override public String failureStage(Exception refusal){
        // Only the exception emitted by this consumed call can identify its inner step.
        return refusal!=null&&refusal==detailedRefusal?detailedStep:failureStage();
    }
    @Override public JsonObject boundary(){return stage("INITIAL","RENEWAL_BOUNDARY",Stage.BOUNDARY,()->{
        // Authenticate the original settled receipt BEFORE constructing any genuine factories.
        var receipt=initial.settledReceipt();management();var clock=receipt.intent().clock().owner();var terms=receipt.intent().terms();
        require(clock.run().equals(owner.run())&&clock.initialFrozenTime()==owner.frozen()&&terms.regularAmount().minorUnits()==2000
            &&terms.regularAmount().currency().equals("EUR")&&terms.interval()==BillingInterval.MONTH&&terms.trialDays()==0&&terms.taxMode()==TaxMode.INCLUSIVE);
        var c=parts();upstream=c.admit(receipt,owner.source());require(upstream.receipt().equals(receipt)&&upstream.sourceSha().equals(owner.source()));
        management();var ready=c.advance().advance(upstream,OwnedFirstRenewal.Step.BOUNDARY);management();var observed=c.boundaryObserver().observe(upstream,ready);
        require(observed instanceof OwnedFirstRenewal.Pending);pending=(OwnedFirstRenewal.Pending)observed;
        require(pending.admission().equals(upstream)&&pending.ready().equals(ready)&&pending.boundary().equals(c.renewals.boundary(upstream))
            &&pending.boundary().period().start().equals(Instant.ofEpochSecond(receipt.periodEnd()))
            &&pending.boundary().period().start().plusSeconds(259201).isBefore(pending.boundary().period().end()));
        return response("RENEWAL_BOUNDARY");
    });}
    @Override public JsonObject armDecline(){return stage("RENEWAL_BOUNDARY","DECLINE_ARMED",Stage.DECLINE_ARM,()->{
        var c=parts();armEvidence=new OwnedBillingFailedRenewalFailureArm(upstream,pending,this::management,c.credentials,c.clients,bean(LoadPaymentAccountPort.class),c.renewals,utc,deadline).arm();
        return response("DECLINE_ARMED");
    });}
    @Override public JsonObject failedRenewal(){return stage("DECLINE_ARMED","FAILED_RENEWAL",Stage.FAILED_RENEWAL,()->{
        detailedStep="FAILED_RENEWAL_CLOCK_ADVANCE";
        management();var c=parts();var ready=c.advance().advance(upstream,OwnedFirstRenewal.Step.FINALIZATION);
        // This READY2 goes straight to the failed service; no successful Paid observer/publisher.
        detailedStep="FAILED_RENEWAL_ADMISSION";
        management();failed=c.failures.admit(upstream,ready,pending.boundary());
        detailedStep="FAILED_RENEWAL_OBSERVATION";
        management();
        var observer=c.failureObserver();
        var diagnostic=new StripeBillingOwnedFailedRenewalObserver.Diagnostic();
        try {
            failure=observer.observe(failed,diagnostic);
        } catch(RuntimeException refusal) {
            var checkpoint=diagnostic.checkpoint();
            if(checkpoint!=null)detailedStep="FAILED_RENEWAL_OBSERVATION_"+checkpoint.name();
            throw refusal;
        }
        detailedStep="FAILED_RENEWAL_EVIDENCE";
        var e=pending.boundary().period().start();require(ready.observation().frozenTime()==e.plusSeconds(3600).getEpochSecond()
            &&failure.graceDeadline().equals(e.plusSeconds(259200))&&failure.period().equals(pending.boundary().period())
            &&failure.invoice().equals(pending.boundary().renewalInvoice()));
        return response("FAILED_RENEWAL");
    });}
    @Override public JsonObject graceLastSecond(){return stage("FAILED_RENEWAL","GRACE_LAST_SECOND",Stage.GRACE_LAST_SECOND,()->{
        detailedStep="GRACE_LAST_SECOND_PREPARATION";
        management();var c=parts();preparation=c.failures.prepareGraceLastSecond(failed);
        detailedStep="GRACE_LAST_SECOND_CLOCK_ADVANCE";
        management();var adapter=c.graceAdapter();
        var diagnostic=new StripeBillingOwnedFailedGraceAdvanceAdapter.Diagnostic();
        try{graceReady=adapter.advance(failed,preparation,diagnostic);}
        catch(RuntimeException refusal){
            var checkpoint=diagnostic.checkpoint();
            if(checkpoint!=null)detailedStep="GRACE_LAST_SECOND_CLOCK_"+checkpoint.name();
            throw refusal;
        }
        detailedStep="GRACE_LAST_SECOND_CLOCK_EVIDENCE";
        require(graceReady.observation().frozenTime()==failure.graceDeadline().minusSeconds(1).getEpochSecond());
        detailedStep="GRACE_LAST_SECOND_ACCESS";
        return accessResponse("GRACE_LAST_SECOND",failure.graceDeadline().minusSeconds(1),AccessDecision.Reason.GRACE,failure.graceDeadline());
    });}
    @Override public JsonObject graceAt(){return stage("GRACE_LAST_SECOND","GRACE_AT",Stage.GRACE_AT,()->{
        management();at=parts().boundaryAdapter().advance(failed,preparation,OwnedFailedGraceBoundary.Phase.AT);
        require(at.observation().frozenTime()==failure.graceDeadline().getEpochSecond());
        return accessResponse("GRACE_AT",failure.graceDeadline(),AccessDecision.Reason.EXPIRED,null);
    });}
    @Override public JsonObject graceAfter(){return stage("GRACE_AT","GRACE_AFTER",Stage.GRACE_AFTER,()->{
        management();after=parts().boundaryAdapter().advance(failed,preparation,OwnedFailedGraceBoundary.Phase.AFTER);
        require(after.observation().frozenTime()==failure.graceDeadline().plusSeconds(1).getEpochSecond());
        return accessResponse("GRACE_AFTER",failure.graceDeadline().plusSeconds(1),AccessDecision.Reason.EXPIRED,null);
    });}
    @Override public JsonObject prepareMethod(){return stage("GRACE_AFTER","METHOD_PREPARED",Stage.METHOD_PREPARE,()->{
        var c=parts();var context=management();var successor=new OwnedFailedRenewalMethod.GraceSuccessor(preparation.id(),after.id(),after.observation(),after.observedAt());
        reservation=c.methods.reserveBrowserMethodAfterGrace(failed,successor,context,true);missingAccess();
        c.setup().prepare(reservation,management());missingAccess();browser=c.browser(reservation);
        action=browser.get(new GetSubscriptionActionUseCase.Query(browserContext("action"),upstream.receipt().intent().subscription(),reservation.setupOperationId()));
        require(action!=null&&action.kind()==SubscriptionProviderModels.ActionKind.PROVIDER_CLIENT);
        var result=response("METHOD_PREPARED");var value=new JsonObject();value.addProperty("kind",action.kind().name());value.addProperty("secret",action.secret());value.addProperty("expires_at",action.expiresAt().toString());result.add("action",value);return result;
    });}
    @Override public JsonObject selectMethod(){return stage("METHOD_PREPARED","METHOD_SELECTED",Stage.METHOD_SELECT,()->{
        detailedStep="METHOD_SELECT_MANAGEMENT";
        management();
        var scope=new SubscriptionConfirmationUseCase.Scope(browserContext("selection"),upstream.receipt().intent().subscription(),reservation.setupOperationId());
        detailedStep="METHOD_SELECT_SELECTION";
        var diagnostic=new OwnedMethodBrowserService.SelectionDiagnostic();
        try{selected=browser.select(scope,action.secret(),diagnostic);}
        catch(RuntimeException refusal){
            if(diagnostic.checkpoint()!=null)detailedStep="METHOD_SELECT_"+diagnostic.checkpoint().name();
            throw refusal;
        }
        detailedStep="METHOD_SELECT_SESSION";
        require(selected.protocol().equals("BROWSER_CONFIRM_V1")&&selected.provider().equals("stripe")&&selected.flow()==SubscriptionClientSession.Flow.SETUP&&selected.attemptId()==null&&selected.capability()==null&&selected.actionToken().equals(action.secret()));
        detailedStep="METHOD_SELECT_ACCESS";
        missingAccess();
        detailedStep="METHOD_SELECT_RESPONSE_BUILD";
        var result=response("METHOD_SELECTED");var session=new JsonObject();session.addProperty("protocol",selected.protocol());session.addProperty("provider",selected.provider());session.addProperty("flow",selected.flow().name());session.addProperty("publishable_key",selected.publishableKey());session.addProperty("client_secret",selected.clientSecret());session.addProperty("action_token",selected.actionToken());session.add("attempt_id",JsonNull.INSTANCE);session.add("capability",JsonNull.INSTANCE);session.addProperty("expires_at",selected.expiresAt().toString());result.add("session",session);return result;
    });}
    @Override public JsonObject confirmMethod(){return stage("METHOD_SELECTED","METHOD_CONSUMED",Stage.METHOD_CONFIRM,()->{
        management();var allowed=browser.confirm(new ConfirmSubscriptionActionUseCase.Command(browserContext("consumption"),upstream.receipt().intent().subscription(),reservation.setupOperationId(),selected.actionToken()));
        require(allowed.browserConfirmAllowed()&&allowed.status()==SubscriptionOperation.Status.RESERVED&&allowed.operationId().equals(reservation.setupOperationId()));
        missingAccess();var result=response("METHOD_CONSUMED");result.addProperty("browser_confirm_allowed",true);result.addProperty("state",allowed.status().name());return result;
    });}
    @Override public JsonObject applyMethod(){return stage("METHOD_CONSUMED","METHOD_READY",Stage.METHOD_APPLY,()->{
        var c=parts();c.binding().bind(reservation,management());c.method().apply(reservation,management());
        c.methods.recordMethodUpdateReply(reservation,management());management();completion=c.methodObserver().observeMethod(reservation);
        management();reservedRecovery=c.methods.reserveRecovery(reservation,completion);
        recovery=new OwnedBillingFailedRenewalRecovery(reservation,completion,reservedRecovery,management(),c.confirmer(),c.methodObserver());
        missingAccess();selected=null;action=null;return response("METHOD_READY");
    });}
    @Override public JsonObject confirmRecovery(){return stage("METHOD_READY","RECOVERY_ACKNOWLEDGED",Stage.RECOVERY_CONFIRM,()->{
        detailedStep="RECOVERY_CONFIRM_MANAGEMENT";
        management();
        var diagnostic=new StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter.Diagnostic();
        detailedStep="RECOVERY_CONFIRM_CONFIRMATION";
        try{recovery.confirm(diagnostic);}
        catch(RuntimeException refusal){
            var checkpoint=diagnostic.checkpoint();
            if(checkpoint!=null)detailedStep="RECOVERY_CONFIRM_CONFIRMATION_"+checkpoint.name();
            throw refusal;
        }
        detailedStep="RECOVERY_CONFIRM_ACCESS";
        missingAccess();
        detailedStep="RECOVERY_CONFIRM_RESPONSE_BUILD";
        var result=response("RECOVERY_ACKNOWLEDGED");
        detailedStep=null;
        return result;
    });}
    @Override public JsonObject observeRecovery(){return stage("RECOVERY_ACKNOWLEDGED","RECOVERED_SETTLED_LOCAL",Stage.RECOVERY_OBSERVE,()->{
        detailedStep="RECOVERY_OBSERVE_MANAGEMENT";
        management();
        detailedStep="RECOVERY_OBSERVE_OBSERVATION";
        var diagnostic=new RecoveryDiagnostic();
        try{recovery.observe(diagnostic);}
        catch(RuntimeException refusal){
            var checkpoint=diagnostic.checkpoint();
            if(checkpoint!=null)detailedStep="RECOVERY_OBSERVE_OBSERVATION_"+checkpoint.name();
            throw refusal;
        }
        detailedStep="RECOVERY_OBSERVE_ACCESS";
        var evaluated=failure.graceDeadline().plusSeconds(1);var n=pending.boundary().period().end();
        return accessResponse("RECOVERED_SETTLED_LOCAL",evaluated,AccessDecision.Reason.PAID,evaluated.plusSeconds(300).isBefore(n)?evaluated.plusSeconds(300):n);
    });}
    private JsonObject accessResponse(String phase,Instant at,AccessDecision.Reason reason,Instant expiry){
        var context=management();var c=parts();var intent=upstream.receipt().intent();AccessDecision decision=null;
        for(int repeat=0;repeat<2;repeat++){
            var view=c.query.get(context.actor(),context.siteId(),intent.subscription());var access=c.query.access(context.actor(),context.siteId(),context.customerId(),intent.terms().productId());
            require(view.access().equals(access)&&access.reason()==reason&&access.evaluatedAt().equals(at)&&Objects.equals(access.validUntil(),expiry)
                &&access.subscriptionId().equals(intent.subscription())&&access.siteId().equals(context.siteId())&&access.customerId().equals(context.customerId())
                &&access.allowed()==(reason==AccessDecision.Reason.GRACE||reason==AccessDecision.Reason.PAID));
            if(decision!=null)require(decision.equals(access));decision=access;
            var subscription=view.subscription();
            require(subscription.billingPrice().equals(intent.terms())&&Objects.equals(subscription.entitledPrice(),intent.terms()));
            if(reason==AccessDecision.Reason.PAID)require(subscription.billingStatus()==Subscription.Status.ACTIVE&&subscription.debt()==null&&view.debt()==null
                &&view.permittedActions().isEmpty()&&view.pendingOperation()==null
                &&subscription.currentPeriod().equals(pending.boundary().period())&&subscription.paidThrough().equals(pending.boundary().period().end()));
            else require(subscription.billingStatus()==Subscription.Status.PAST_DUE&&subscription.debt()!=null&&subscription.debt().billingRecordId().equals(failure.billId())
                &&subscription.debt().unpaidPeriod().equals(failure.period())&&subscription.debt().graceDeadline().equals(failure.graceDeadline()));
        }
        var result=response(phase);result.addProperty("access_reason",decision.reason().name());result.addProperty("access_allowed",decision.allowed());result.addProperty("access_evaluated_at",decision.evaluatedAt().toString());
        if(expiry==null)result.add("access_valid_until",JsonNull.INSTANCE);else result.addProperty("access_valid_until",expiry.toString());return result;
    }
    private void missingAccess(){
        var context=management();var c=parts();var intent=upstream.receipt().intent();
        missing(()->c.query.get(context.actor(),context.siteId(),intent.subscription()));
        missing(()->c.query.access(context.actor(),context.siteId(),context.customerId(),intent.terms().productId()));
    }
    private static void missing(Supplier<?> read){try{read.get();}catch(IllegalStateException expected){require("OWNED_TIME_AUTHORITY_MISSING".equals(expected.getMessage()));return;}throw denied();}
    private JsonObject response(String phase){var result=initial.status();result.addProperty("phase",phase);result.addProperty("code","OK");
        result.addProperty("source_sha",owner.source());result.addProperty("original_frozen_time",owner.frozen());
        if(upstream!=null){
            id(result,"renewal_admission_id",upstream.id());id(result,"initial_bill_id",upstream.bill());id(result,"initial_payment_id",upstream.payment());
            result.addProperty("initial_invoice_id",upstream.invoice());result.addProperty("initial_allocation_id",upstream.allocation());result.addProperty("initial_payment_intent_id",upstream.paymentIntent());
        }
        if(pending!=null){
            var boundary=pending.boundary();var ready=pending.ready();id(result,"boundary_id",boundary.id());id(result,"boundary_ready_id",ready.id());
            id(result,"boundary_advance_id",ready.ack().advance().id());id(result,"boundary_operation_id",ready.ack().operation().id());
            result.addProperty("boundary_time",boundary.period().start().toString());result.addProperty("period_start",boundary.period().start().toString());result.addProperty("period_end",boundary.period().end().toString());
            result.addProperty("renewal_invoice_id",boundary.renewalInvoice());id(result,"pending_graph_invocation_id",pending.claim().invocation());result.addProperty("pending_graph_accepted_at",pending.graphAcceptedAt().toString());
        }
        if(armEvidence!=null){id(result,"arm_admission_id",armEvidence.admission());id(result,"arm_boundary_id",armEvidence.boundary());id(result,"arm_run_id",armEvidence.run());
            result.addProperty("arm_payment_method_id",armEvidence.attachedMethod());result.addProperty("arm_acknowledged_at",armEvidence.acknowledgedAt().toString());}
        if(failed!=null){id(result,"failed_admission_id",failed.id());var ready=failed.ready();id(result,"finalization_ready_id",ready.id());
            id(result,"finalization_advance_id",ready.ack().advance().id());id(result,"finalization_operation_id",ready.ack().operation().id());result.addProperty("finalization_time",ready.observation().frozenTime());}
        if(failure!=null){id(result,"failure_claim_id",failure.claimId());id(result,"failed_bill_id",failure.billId());result.addProperty("failed_allocation_id",failure.allocation());
            result.addProperty("failed_payment_intent_id",failure.paymentIntent());result.addProperty("grace_deadline",failure.graceDeadline().toString());result.addProperty("failed_recorded_at",failure.recordedAt().toString());}
        if(preparation!=null){id(result,"grace_preparation_id",preparation.id());result.addProperty("grace_target",preparation.target());}
        if(graceReady!=null){id(result,"grace_ready_id",graceReady.id());id(result,"grace_operation_id",graceReady.ack().operation().id());
            id(result,"grace_invocation_id",graceReady.ack().invocation());result.addProperty("grace_frozen_time",graceReady.observation().frozenTime());}
        if(at!=null)boundaryEvidence(result,"grace_at",at);if(after!=null)boundaryEvidence(result,"grace_after",after);
        if(reservation!=null){id(result,"method_reservation_id",reservation.id());id(result,"method_operation_id",reservation.setupOperationId());}
        if(completion!=null){id(result,"method_claim_id",completion.claimId());id(result,"method_setup_step_id",completion.setupStepId());id(result,"method_update_step_id",completion.methodStepId());
            result.addProperty("method_completed_at",completion.completedAt().toString());result.addProperty("setup_intent_id",completion.setupIntent());result.addProperty("replacement_method_id",completion.method());}
        if(reservedRecovery!=null){var original=reservedRecovery.association();id(result,"recovery_operation_id",original.operationId());id(result,"recovery_bill_id",original.billId());
            result.addProperty("recovery_allocation_id",original.allocation());result.addProperty("recovery_payment_intent_id",original.paymentIntent());}
        if(recovery!=null){
            recovery.confirmation().ifPresent(ack->recoveryConfirmationEvidence(result,ack));
            recovery.publication().ifPresent(value->{var publication=value.publication();id(result,"recovery_publication_claim_id",publication.claimId());id(result,"recovery_publication_admission_id",publication.admissionId());
                id(result,"recovery_publication_operation_id",publication.operationId());id(result,"recovery_publication_bill_id",publication.billId());id(result,"recovery_publication_reservation_id",value.reservationId());id(result,"recovery_payment_id",publication.paymentId());
                result.addProperty("recovery_published_at",publication.publishedAt().toString());result.addProperty("recovery_revision",publication.revision());result.addProperty("recovery_observation_sequence",value.observationSequence());});
        }
        return result;
    }
    private static void id(JsonObject result,String name,UUID value){result.addProperty(name,value.toString());}
    static void recoveryConfirmationEvidence(JsonObject result,OwnedRecoveryConfirm.ConfirmationAck ack){
        id(result,"recovery_confirm_operation_id",ack.operation().id());
        id(result,"recovery_confirm_resource_id",ack.operation().resourceId());
        result.addProperty("recovery_confirm_operation_version",ack.operation().version());
        result.addProperty("recovery_confirm_status",ack.operation().status().name());
        result.addProperty("recovery_acknowledged_at",ack.acknowledgedAt().truncatedTo(ChronoUnit.MICROS).toString());
        result.addProperty("recovery_confirm_payment_intent_id",ack.reply().paymentIntent());
        result.addProperty("recovery_charge_id",ack.reply().charge());
    }
    private static void boundaryEvidence(JsonObject result,String prefix,OwnedFailedGraceBoundary.BoundaryReady value){
        id(result,prefix+"_ready_id",value.id());id(result,prefix+"_operation_id",value.ack().operation().id());id(result,prefix+"_invocation_id",value.ack().invocation());result.addProperty(prefix+"_frozen_time",value.observation().frozenTime());
    }
    @Override public JsonObject status(){var phase=state.phase();if(phase.equals("INITIAL"))return initial.status();var result=response(phase);result.addProperty("code",phase.equals("STOPPED")?"FAILED_RENEWAL_STOPPED":"OK");return result;}
    @Override public void abort(){if(state.requestAbort())cleanup();}
    @Override public boolean awaitQuiescence(long untilNanos)throws InterruptedException{return state.awaitQuiescence(untilNanos);}
    private synchronized void cleanup(){
        if(cleaned)return;cleaned=true;selected=null;action=null;
        // Each concrete delegate cleans only its owned claim. Do not synthesize a terminal
        // successor owner or call a generic method-stop on someone else's producer.
        if(upstream!=null&&components!=null&&reservation==null)try{components.renewals.stop(upstream,"AUTHORITY_DRIFT",utc.instant());}catch(RuntimeException ignored){}
        initial.abort();
    }
    private static void require(boolean valid){if(!valid)throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("FAILED_RENEWAL_STOPPED");}
    @Override public String toString(){return "OwnedBillingFailedRenewalSandboxScenario[redacted]";}

    /** Actually used exclusive local dispatcher; losing callers never receive cleanup tickets. */
    static final class State {
        private final Clock utc;private final Instant deadline;private String phase="INITIAL";private long sequence,owner;private boolean cancelled;
        private int transports;
        State(Clock utc,Instant deadline){this.utc=Objects.requireNonNull(utc);this.deadline=Objects.requireNonNull(deadline);}
        synchronized long enter(String expected,String running){
            if(owner!=0)throw denied();check();if(!phase.equals(expected)){phase="STOPPED";throw denied();}
            owner=++sequence;phase=running;OwnedBillingFailedRenewalRuntime.ScopedClients.bind(this,owner);return owner;
        }
        synchronized void complete(long ticket,String running,String next){
            if(ticket!=owner||owner==0||transports!=0||!phase.equals(running))throw denied();check();phase=next;retire();
        }
        private void retire(){owner=0;OwnedBillingFailedRenewalRuntime.ScopedClients.unbind();notifyAll();}
        synchronized boolean fail(long ticket){if(ticket!=owner||owner==0)return false;phase="STOPPED";retire();return true;}
        synchronized String phase(){if(!utc.instant().isBefore(deadline)&&owner==0)phase="STOPPED";return phase;}
        synchronized boolean stoppedIdle(){return owner==0&&phase.equals("STOPPED");}
        synchronized boolean requestAbort(){cancelled=true;if(owner!=0)return false;phase="STOPPED";notifyAll();return true;}
        synchronized void beginTransport(long ticket){checkActive();if(owner!=ticket)throw denied();transports++;}
        synchronized void endTransport(){transports--;notifyAll();}
        synchronized boolean awaitQuiescence(long until)throws InterruptedException{
            while(owner!=0||transports!=0){
                long left=until-System.nanoTime();if(left<=0)return false;
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this,left);
            }
            return cancelled&&phase.equals("STOPPED")&&System.nanoTime()<until;
        }
        synchronized void checkActive(){if(owner==0)throw denied();check();}
        private void check(){if(cancelled||phase.equals("STOPPED")||!utc.instant().isBefore(deadline)){if(owner==0)phase="STOPPED";throw denied();}}
    }
}
