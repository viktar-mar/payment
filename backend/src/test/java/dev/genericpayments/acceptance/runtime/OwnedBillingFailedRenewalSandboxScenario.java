package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.service.OwnedMethodBrowserService;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Fixed actual producer chain. Descriptive JSON never supplies authority to the next producer. */
public final class OwnedBillingFailedRenewalSandboxScenario implements OwnedBillingFailedRenewalOperations {
    private enum Stage { UNCLASSIFIED, INITIAL, BOUNDARY, DECLINE_ARM, FAILED_RENEWAL, GRACE_LAST_SECOND, GRACE_AT, GRACE_AFTER,
        METHOD_PREPARE, METHOD_SELECT, METHOD_CONFIRM, METHOD_APPLY, RECOVERY_CONFIRM, RECOVERY_OBSERVE }
    private final OwnedBillingFirstPaymentScenario initial;private final OwnedBillingFirstPaymentRuntime.Admission owner;
    private final Function<Class<?>,Object> beans;private final Clock utc;private final Instant deadline;private final State state;
    private volatile Stage failureStage=Stage.UNCLASSIFIED;private OwnedBillingFailedRenewalComponents components;
    private OwnedFirstRenewal.Admission upstream;private OwnedFirstRenewal.Pending pending;
    private FailureArmEvidence armEvidence;
    private OwnedFailedRenewal.Admission failed;private OwnedFailedRenewal.Failure failure;
    private OwnedFailedRenewal.GraceLastSecondPreparation preparation;private OwnedFailedGraceAdvance.GraceAdvanceReady graceReady;
    private OwnedFailedGraceBoundary.BoundaryReady at,after;
    private OwnedFailedRenewalMethod.Reservation reservation;private OwnedMethodBrowserService browser;
    private SubscriptionProviderModels.NextAction action;private SubscriptionConfirmationUseCase.Session selected;
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
        try{failureStage=checkpoint;var result=work.get();state.complete(ticket,next+"_RUNNING",next);return result;}
        catch(RuntimeException invalid){if(state.fail(ticket))cleanup();throw denied();}
        catch(Error invalid){if(state.fail(ticket))cleanup();throw invalid;}
    }
    private JsonObject initial(Supplier<JsonObject> work){return stage("INITIAL","INITIAL",Stage.INITIAL,work);}
    @Override public JsonObject prepare(JsonObject request){return initial(()->initial.prepare(request));}
    @Override public JsonObject confirm(JsonObject request){return initial(()->initial.confirm(request));}
    @Override public JsonObject observe(){return initial(initial::observe);}
    @Override public String prepareFailureStage(){return initial.prepareFailureStage();}
    @Override public String failureStage(){return failureStage.name();}
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
        management();var c=parts();var ready=c.advance().advance(upstream,OwnedFirstRenewal.Step.FINALIZATION);
        // This READY2 goes straight to the failed service; no successful Paid observer/publisher.
        management();failed=c.failures.admit(upstream,ready,pending.boundary());management();failure=c.failureObserver().observe(failed);
        var e=pending.boundary().period().start();require(ready.observation().frozenTime()==e.plusSeconds(3600).getEpochSecond()
            &&failure.graceDeadline().equals(e.plusSeconds(259200))&&failure.period().equals(pending.boundary().period())
            &&failure.invoice().equals(pending.boundary().renewalInvoice()));
        return response("FAILED_RENEWAL");
    });}
    @Override public JsonObject graceLastSecond(){return stage("FAILED_RENEWAL","GRACE_LAST_SECOND",Stage.GRACE_LAST_SECOND,()->{
        management();var c=parts();preparation=c.failures.prepareGraceLastSecond(failed);management();graceReady=c.graceAdapter().advance(failed,preparation);
        require(graceReady.observation().frozenTime()==failure.graceDeadline().minusSeconds(1).getEpochSecond());
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
        management();selected=browser.select(new SubscriptionConfirmationUseCase.Scope(browserContext("selection"),upstream.receipt().intent().subscription(),reservation.setupOperationId()),action.secret());
        require(selected.protocol().equals("BROWSER_CONFIRM_V1")&&selected.flow()==SubscriptionClientSession.Flow.SETUP&&selected.attemptId()==null&&selected.capability()==null&&selected.actionToken().equals(action.secret()));
        missingAccess();var result=response("METHOD_SELECTED");var session=new JsonObject();session.addProperty("protocol",selected.protocol());session.addProperty("provider",selected.provider());session.addProperty("flow",selected.flow().name());session.addProperty("publishable_key",selected.publishableKey());session.addProperty("client_secret",selected.clientSecret());session.addProperty("action_token",selected.actionToken());session.add("attempt_id",JsonNull.INSTANCE);session.add("capability",JsonNull.INSTANCE);session.addProperty("expires_at",selected.expiresAt().toString());result.add("session",session);return result;
    });}
    @Override public JsonObject confirmMethod(){return stage("METHOD_SELECTED","METHOD_CONSUMED",Stage.METHOD_CONFIRM,()->{
        management();var allowed=browser.confirm(new ConfirmSubscriptionActionUseCase.Command(browserContext("consumption"),upstream.receipt().intent().subscription(),reservation.setupOperationId(),selected.actionToken()));
        require(allowed.browserConfirmAllowed()&&allowed.status()==SubscriptionOperation.Status.RESERVED&&allowed.operationId().equals(reservation.setupOperationId()));
        missingAccess();var result=response("METHOD_CONSUMED");result.addProperty("browser_confirm_allowed",true);result.addProperty("state",allowed.status().name());return result;
    });}
    @Override public JsonObject applyMethod(){return stage("METHOD_CONSUMED","METHOD_READY",Stage.METHOD_APPLY,()->{
        var c=parts();c.binding().bind(reservation,management());c.method().apply(reservation,management());
        c.methods.recordMethodUpdateReply(reservation,management());management();var completion=c.methodObserver().observeMethod(reservation);
        management();var reservedRecovery=c.methods.reserveRecovery(reservation,completion);
        recovery=new OwnedBillingFailedRenewalRecovery(reservation,completion,reservedRecovery,management(),c.confirmer(),c.methodObserver());
        missingAccess();selected=null;action=null;return response("METHOD_READY");
    });}
    @Override public JsonObject confirmRecovery(){return stage("METHOD_READY","RECOVERY_ACKNOWLEDGED",Stage.RECOVERY_CONFIRM,()->{
        management();recovery.confirm();missingAccess();return response("RECOVERY_ACKNOWLEDGED");
    });}
    @Override public JsonObject observeRecovery(){return stage("RECOVERY_ACKNOWLEDGED","RECOVERED_SETTLED_LOCAL",Stage.RECOVERY_OBSERVE,()->{
        management();recovery.observe();var evaluated=failure.graceDeadline().plusSeconds(1);var n=pending.boundary().period().end();
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
        if(upstream!=null)result.addProperty("renewal_admission_id",upstream.id().toString());
        if(pending!=null){result.addProperty("boundary_time",pending.boundary().period().start().toString());result.addProperty("period_start",pending.boundary().period().start().toString());result.addProperty("period_end",pending.boundary().period().end().toString());}
        if(failed!=null)result.addProperty("failed_admission_id",failed.id().toString());if(failure!=null)result.addProperty("grace_deadline",failure.graceDeadline().toString());
        if(reservation!=null)result.addProperty("method_operation_id",reservation.setupOperationId().toString());return result;
    }
    @Override public JsonObject status(){var phase=state.phase();if(phase.equals("INITIAL"))return initial.status();var result=response(phase);result.addProperty("code",phase.equals("STOPPED")?"FAILED_RENEWAL_STOPPED":"OK");return result;}
    @Override public void abort(){if(state.requestAbort())cleanup();}
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
        State(Clock utc,Instant deadline){this.utc=Objects.requireNonNull(utc);this.deadline=Objects.requireNonNull(deadline);}
        synchronized long enter(String expected,String running){
            if(owner!=0)throw denied();check();if(!phase.equals(expected)){phase="STOPPED";throw denied();}
            owner=++sequence;phase=running;return owner;
        }
        synchronized void complete(long ticket,String running,String next){
            if(ticket!=owner||owner==0||!phase.equals(running))throw denied();check();phase=next;owner=0;
        }
        synchronized boolean fail(long ticket){if(ticket!=owner||owner==0)return false;phase="STOPPED";owner=0;return true;}
        synchronized String phase(){if(!utc.instant().isBefore(deadline)&&owner==0)phase="STOPPED";return phase;}
        synchronized boolean stoppedIdle(){return owner==0&&phase.equals("STOPPED");}
        synchronized boolean requestAbort(){if(owner!=0){cancelled=true;return false;}phase="STOPPED";return true;}
        synchronized void checkActive(){if(owner==0)throw denied();check();}
        private void check(){if(cancelled||phase.equals("STOPPED")||!utc.instant().isBefore(deadline)){if(owner==0)phase="STOPPED";throw denied();}}
    }
}
