package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
class SubscriptionCancellationIntegrationTest extends SubscriptionRecoveryFixture {
    @Autowired CancelSubscriptionUseCase cancellation;
    @Autowired WithdrawSubscriptionCancellationUseCase withdrawal;
    @Autowired QuerySubscriptionsUseCase queries;
    void periodEndAction(UUID id,boolean expected){assertThat(queries.get(actor,site,id).permittedActions().contains(SubscriptionView.PermittedAction.CANCEL_PERIOD_END)).isEqualTo(expected);}
    void allowImmediate(){var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,3,true,false,false,Set.of()),p.version());}
    CancelSubscriptionUseCase.Command cancel(UUID id,String key,CancelSubscriptionUseCase.Mode mode){return new CancelSubscriptionUseCase.Command(context(key),id,mode,id);}
    WithdrawSubscriptionCancellationUseCase.Command withdraw(UUID id,String key){return new WithdrawSubscriptionCancellationUseCase.Command(context(key),id);}
    @Test void declineReceiptDoesNotFinishLifecycleAndScopedDeclineReleasesOnlyTheOperation(){
        var id=paid().subscriptionId();allowImmediate();var before=sub(id);controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.DECLINE);
        var result=cancellation.cancel(cancel(id,"decline",CancelSubscriptionUseCase.Mode.IMMEDIATE));
        assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(sub(id)).isEqualTo(before);
        drain();assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);assertThat(sub(id)).isEqualTo(before);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("refunds")).isZero();
    }
    SubscriptionCommandContext admin(UUID user,String key){
        var session=UUID.randomUUID();var username="admin-"+user;
        jdbc.update("insert into admin_users(id,username,password_hash,status,created_at) values (?,?,'unused','ACTIVE',?) on conflict(id) do nothing",user,username,Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into admin_sessions(id,admin_user_id,token_hash,created_at,last_seen_at,expires_at,absolute_expires_at) values (?,?,?,?,?,?,?)",session,user,UUID.randomUUID().toString(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME.plusSeconds(1200)),Timestamp.from(INITIAL_TIME.plusSeconds(2400)));
        return new SubscriptionCommandContext(new SubscriptionActor.Admin(new dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin(new dev.genericpayments.platform.admin.domain.AdminUserId(user),username,new dev.genericpayments.platform.admin.domain.AdminSessionId(session),INITIAL_TIME.plusSeconds(1200))),site,customer.id(),null,key,"admin-cancel");
    }
    @Test void adminOriginalKeySurvivesNewSessionButCannotBeBypassedByAnotherAdmin(){
        var id=paid().subscriptionId();allowImmediate();controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.UNKNOWN);var user=UUID.randomUUID();
        var r=cancellation.cancel(new CancelSubscriptionUseCase.Command(admin(user,"stable"),id,CancelSubscriptionUseCase.Mode.IMMEDIATE,id));drain();
        jdbc.update("update admin_sessions set revoked_at=? where admin_user_id=?",Timestamp.from(INITIAL_TIME),user);
        assertThat(cancellation.cancel(new CancelSubscriptionUseCase.Command(admin(user,"stable"),id,CancelSubscriptionUseCase.Mode.IMMEDIATE,id)).operationId()).isEqualTo(r.operationId());
        assertThatThrownBy(()->cancellation.cancel(new CancelSubscriptionUseCase.Command(admin(UUID.randomUUID(),"stable"),id,CancelSubscriptionUseCase.Mode.IMMEDIATE,id))).isInstanceOf(PaymentApplicationException.class);
        assertThat(count("fake_subscription_operations")).isEqualTo(2);
    }
    @Test void partnerOriginalKeyIsStableAndDifferentKeyCannotBypassUnknownCancellation(){
        var id=paid().subscriptionId();allowImmediate();controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.UNKNOWN);
        var keys=bean(dev.genericpayments.platform.partners.application.port.out.SiteApiKeyPort.class);
        var key=new dev.genericpayments.platform.partners.domain.SiteApiKey(new dev.genericpayments.platform.partners.domain.SiteApiKeyId(UUID.randomUUID()),site,"abcdefghijkl","$argon2id$test",Set.of(dev.genericpayments.platform.partners.domain.PartnerScope.SUBSCRIPTIONS_MANAGE),dev.genericpayments.platform.partners.domain.SiteApiKey.Status.ACTIVE,INITIAL_TIME,null,null,null);keys.insert(key);
        var context=new SubscriptionCommandContext(new SubscriptionActor.Partner(new dev.genericpayments.platform.partners.application.port.in.AuthenticatedSite(site,key.id(),key.scopes())),site,customer.id(),null,"stable","partner-cancel");
        var r=cancellation.cancel(new CancelSubscriptionUseCase.Command(context,id,CancelSubscriptionUseCase.Mode.IMMEDIATE,id));drain();
        assertThat(cancellation.cancel(new CancelSubscriptionUseCase.Command(context,id,CancelSubscriptionUseCase.Mode.IMMEDIATE,id)).operationId()).isEqualTo(r.operationId());
        var other=new dev.genericpayments.platform.partners.domain.SiteApiKey(new dev.genericpayments.platform.partners.domain.SiteApiKeyId(UUID.randomUUID()),site,"mnopqrstuvwx","$argon2id$test",key.scopes(),key.status(),INITIAL_TIME,null,null,null);keys.insert(other);
        var otherContext=new SubscriptionCommandContext(new SubscriptionActor.Partner(new dev.genericpayments.platform.partners.application.port.in.AuthenticatedSite(site,other.id(),other.scopes())),site,customer.id(),null,"stable","partner-cancel");
        assertThatThrownBy(()->cancellation.cancel(new CancelSubscriptionUseCase.Command(otherContext,id,CancelSubscriptionUseCase.Mode.IMMEDIATE,id))).isInstanceOf(PaymentApplicationException.class);
        assertThat(count("fake_subscription_operations")).isEqualTo(2);
    }
    @Test void authoritativeExternalScheduleStillProjectsOnlyTheLiveConfirmedBoundary(){
        var id=paid().subscriptionId();var s=view(id);var boundary=sub(id).paidThrough();
        apply(id,new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),boundary.plusSeconds(86400),s.invoices(),s.operationKey(),s.paymentMethodReady(),s.providerRevision()+1,s.observedAt()),"external-unpaid-boundary");
        assertThat(sub(id).cancellation()).isNull();
        apply(id,new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),boundary,s.invoices(),s.operationKey(),s.paymentMethodReady(),s.providerRevision()+2,s.observedAt()),"external-paid-boundary");
        assertThat(sub(id).cancellation()).isNotNull();assertThat(sub(id).cancellation().effectiveAt()).isEqualTo(boundary);
    }
    @Test void unknownScheduleResolvedAfterDeadlineRetainsOriginalBoundaryAndCreatesNoRenewalCash(){
        var id=paid().subscriptionId();var boundary=sub(id).paidThrough();controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.UNKNOWN);
        var result=cancellation.cancel(cancel(id,"late-schedule",CancelSubscriptionUseCase.Mode.PERIOD_END));drain();
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.SUCCESS);advance(boundary.plusSeconds(60));
        assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("fake_subscription_invoices")).isEqualTo(1);
    }
    @Test void delayedWithdrawalFactIsScopedAndCannotLeaveAnExpiredLocalSchedule(){
        var id=paid().subscriptionId();cancellation.cancel(cancel(id,"schedule",CancelSubscriptionUseCase.Mode.PERIOD_END));drain();var boundary=sub(id).cancellation().effectiveAt();
        var result=withdrawal.withdraw(withdraw(id,"withdraw"));var o=op(result.operationId());
        var snapshot=restartedProvider().retrieve(new Lookup(account,id,sub(id).providerReference(),o.providerOperationKey()));
        TestClock.set(boundary.plusSeconds(1));apply(id,snapshot,"delayed-withdrawal");
        assertThat(sub(id).cancellation()).isNull();assertThat(op(o.id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(sub(id).nonTerminal()).isTrue();
    }
    @ParameterizedTest @EnumSource(value=FakeSubscriptionControlPort.Scenario.class,names={"UNKNOWN","PROCESSING"})
    void unresolvedWithdrawalCannotBlockConfirmedCancellationAtDeadline(FakeSubscriptionControlPort.Scenario scenario){
        expiredWithdrawal(scenario,true);
    }
    @ParameterizedTest @EnumSource(value=FakeSubscriptionControlPort.Scenario.class,names={"UNKNOWN","PROCESSING"})
    void withdrawalFirstResolvedAfterDeadlineCannotUndoCancellation(FakeSubscriptionControlPort.Scenario scenario){
        expiredWithdrawal(scenario,false);
    }
    @ParameterizedTest @EnumSource(value=FakeSubscriptionControlPort.Scenario.class,names={"UNKNOWN","PROCESSING"})
    void withdrawalResolvedBeforeDeadlineRemainsAppliedWhenFactArrivesLate(FakeSubscriptionControlPort.Scenario scenario){
        var id=paid().subscriptionId();cancellation.cancel(cancel(id,"schedule",CancelSubscriptionUseCase.Mode.PERIOD_END));drain();var boundary=sub(id).cancellation().effectiveAt();
        controls.scenario(account.id(),id,scenario);var result=withdrawal.withdraw(withdraw(id,"timely-withdraw"));drain();var operation=op(result.operationId());
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.SUCCESS);
        TestClock.set(boundary.minusSeconds(60));controls.advance(account.id(),boundary.minusSeconds(60));
        var snapshot=restartedProvider().retrieve(new Lookup(account,id,sub(id).providerReference(),operation.providerOperationKey()));
        assertThat(snapshot.operationObservation().state()).isEqualTo(OperationState.APPLIED);assertThat(snapshot.cancellationAt()).isNull();
        TestClock.set(boundary.plusSeconds(1));apply(id,snapshot,"delayed-timely-resolution");
        assertThat(op(operation.id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(sub(id).cancellation()).isNull();assertThat(sub(id).nonTerminal()).isTrue();
        assertThat(count("payments")).isEqualTo(1);advance(boundary.plusSeconds(1));assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);assertThat(count("payments")).isEqualTo(2);
    }
    void expiredWithdrawal(FakeSubscriptionControlPort.Scenario scenario,boolean advanceWhileUnresolved){
        var id=paid().subscriptionId();var boundary=sub(id).paidThrough();
        cancellation.cancel(cancel(id,"schedule",CancelSubscriptionUseCase.Mode.PERIOD_END));drain();
        price=price(0);var other=paid().subscriptionId();
        controls.scenario(account.id(),id,scenario);var result=withdrawal.withdraw(withdraw(id,"uncertain-withdraw"));drain();var operation=op(result.operationId());
        assertThat(operation.status()).isEqualTo(scenario==FakeSubscriptionControlPort.Scenario.UNKNOWN?SubscriptionOperation.Status.UNKNOWN:SubscriptionOperation.Status.PROCESSING);
        var receipt=jdbc.queryForObject("select reply::text from fake_subscription_operations where payment_account_id=? and operation_key=?",String.class,account.id().value(),operation.providerOperationKey());
        if(advanceWhileUnresolved){
            assertThatCode(()->advance(boundary)).doesNotThrowAnyException();
            assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
            assertThat(op(operation.id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
            assertThat(sub(other).currentPeriod().start()).isEqualTo(boundary);
        }
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.SUCCESS);
        assertThatCode(()->advance(boundary.plusSeconds(60))).doesNotThrowAnyException();
        assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(new SubscriptionAccessPolicy().evaluate(sub(id),boundary.plusSeconds(60)).allowed()).isFalse();
        assertThat(op(operation.id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
        var observed=restartedProvider().retrieve(new Lookup(account,id,sub(id).providerReference(),operation.providerOperationKey()));
        assertThat(observed.operationObservation().state()).isEqualTo(OperationState.DECLINED);
        assertThat(observed.invoices()).hasSize(1);assertThat(observed.cancellationAt()).isNull();
        assertThat(jdbc.queryForObject("select reply::text from fake_subscription_operations where payment_account_id=? and operation_key=?",String.class,account.id().value(),operation.providerOperationKey())).isEqualTo(receipt);
        assertThat(withdrawal.withdraw(withdraw(id,"uncertain-withdraw")).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
        assertThat(sub(other).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);assertThat(sub(other).currentPeriod().start()).isEqualTo(boundary);
        assertThat(count("payments")).isEqualTo(3);assertThat(count("fake_subscription_invoices")).isEqualTo(3);assertThat(count("refunds")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from subscription_events where subscription_id=? and event_type='CANCEL_WITHDRAWN'",Integer.class,id)).isZero();
        var terminal=sub(id);advance(boundary.plusSeconds(120));assertThat(sub(id)).isEqualTo(terminal);assertThat(count("payments")).isEqualTo(3);
    }
    @Test void scheduledCancellationAndWithdrawalRequireProviderFactsAndHaveNoFinancialEffects(){
        var id=paid().subscriptionId();var before=sub(id);var request=cancel(id,"schedule",CancelSubscriptionUseCase.Mode.PERIOD_END);
        periodEndAction(id,true);
        var scheduled=cancellation.cancel(request);assertThat(scheduled).isNotNull();assertThat(sub(id)).isEqualTo(before);
        drain();assertThat(sub(id).cancellation().effectiveAt()).isEqualTo(before.paidThrough());assertThat(sub(id).displayState(INITIAL_TIME)).isEqualTo("CANCEL_AT_PERIOD_END");
        assertThat(op(scheduled.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(subscriptions.hasBlockingCustomerSubscriptions(site,customer.id())).isTrue();
        assertThatThrownBy(()->bean(dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase.class).anonymize(new dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase.Command(site,customer.id(),customer.id().value(),"erase","ADMIN",UUID.randomUUID().toString(),"task10","127.0.0.1"))).isInstanceOf(PaymentApplicationException.class).hasMessage("Finish subscriptions before anonymization");
        var withdrawn=withdrawal.withdraw(withdraw(id,"withdraw"));assertThat(withdrawn).isNotNull();assertThat(sub(id).cancellation()).isNotNull();
        drain();assertThat(sub(id).cancellation()).isNull();assertThat(op(withdrawn.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(withdrawal.withdraw(withdraw(id,"withdraw")).operationId()).isEqualTo(withdrawn.operationId());
        assertThat(cancellation.cancel(request).operationId()).isEqualTo(scheduled.operationId());
        assertThat(count("payments")).isEqualTo(1);assertThat(count("refunds")).isZero();assertThat(count("fake_subscription_invoices")).isEqualTo(1);
        assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).contains("CANCEL_SCHEDULED","CANCEL_WITHDRAWN");
    }
    @Test void periodEndTerminalFactPrecedesRenewalAndCannotBeWithdrawnAtDeadline(){
        var id=paid().subscriptionId();var boundary=sub(id).paidThrough();var r=cancellation.cancel(cancel(id,"schedule",CancelSubscriptionUseCase.Mode.PERIOD_END));assertThat(r).isNotNull();drain();
        TestClock.set(boundary);freshActor();assertThatThrownBy(()->withdrawal.withdraw(withdraw(id,"too-late"))).isInstanceOf(PaymentApplicationException.class);
        assertThat(sub(id).nonTerminal()).isTrue();assertThat(new SubscriptionAccessPolicy().evaluate(sub(id),boundary).allowed()).isFalse();
        advance(boundary);assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("fake_subscription_invoices")).isEqualTo(1);assertThat(count("refunds")).isZero();
    }
    @Test void trialPeriodEndUsesExactTrialEndAndCreatesNoCash(){
        price=price(5);var id=paid().subscriptionId();var end=INITIAL_TIME.plusSeconds(5*86400);assertThat(sub(id).trial().end()).isEqualTo(end);
        periodEndAction(id,true);
        assertThat(cancellation.cancel(cancel(id,"trial-schedule",CancelSubscriptionUseCase.Mode.PERIOD_END))).isNotNull();drain();
        assertThat(sub(id).cancellation().effectiveAt()).isEqualTo(end);advance(end);assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(count("payments")).isZero();assertThat(count("fake_subscription_invoices")).isZero();
    }
    @Test void unpaidRenewalPeriodIsNeverUsedAsCancellationBoundary(){
        var id=paid().subscriptionId();controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.DECLINE);advance(sub(id).paidThrough());
        var before=sub(id);assertThat(view(id).period().end()).isAfter(before.paidThrough());
        periodEndAction(id,false);
        assertThatThrownBy(()->cancellation.cancel(cancel(id,"unpaid-end",CancelSubscriptionUseCase.Mode.PERIOD_END))).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->cancellation.cancel(cancel(id,"past-due",CancelSubscriptionUseCase.Mode.IMMEDIATE))).isInstanceOf(PaymentApplicationException.class);
        assertThat(sub(id)).isEqualTo(before);assertThat(count("subscription_operations")).isEqualTo(1);
    }
    @Test void immediateLiveAccessRequiresPolicyAndExactSubscriptionConfirmation(){
        var id=paid().subscriptionId();var before=sub(id);
        assertThatThrownBy(()->cancellation.cancel(cancel(id,"denied",CancelSubscriptionUseCase.Mode.IMMEDIATE))).isInstanceOf(PaymentApplicationException.class);
        allowImmediate();
        for(UUID confirmation:Arrays.asList(null,UUID.randomUUID()))assertThatThrownBy(()->cancellation.cancel(new CancelSubscriptionUseCase.Command(context("confirmation"),id,CancelSubscriptionUseCase.Mode.IMMEDIATE,confirmation))).isInstanceOf(PaymentApplicationException.class);
        assertThat(sub(id)).isEqualTo(before);assertThat(count("subscription_operations")).isEqualTo(1);
        assertThat(cancellation.cancel(cancel(id,"confirmed",CancelSubscriptionUseCase.Mode.IMMEDIATE))).isNotNull();drain();assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
    }
    @Test void pausedFinishKeepsProductSlotUntilConfirmationEvenWhenSiteDisallowsImmediate(){
        price=price(1);var id=paid().subscriptionId();advance(sub(id).trial().end());assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.PAUSED);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.PROCESSING);
        var r=cancellation.cancel(new CancelSubscriptionUseCase.Command(context("finish"),id,CancelSubscriptionUseCase.Mode.IMMEDIATE,null));assertThat(r).isNotNull();drain();
        assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.PAUSED);assertThatThrownBy(this::start).isInstanceOf(RuntimeException.class);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.SUCCESS);advance(view(id).observedAt());
        assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);assertThat(start().subscriptionId()).isNotEqualTo(id);assertThat(count("payments")).isZero();
    }
    @Test void unknownCancelReplaysOriginalKeyAndBlocksOtherMutationsUntilPositiveResolution(){
        var id=paid().subscriptionId();allowImmediate();controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.UNKNOWN);
        var command=cancel(id,"unknown",CancelSubscriptionUseCase.Mode.IMMEDIATE);var result=cancellation.cancel(command);assertThat(result).isNotNull();drain();
        assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
        freshActor();assertThat(cancellation.cancel(cancel(id,"unknown",CancelSubscriptionUseCase.Mode.IMMEDIATE)).operationId()).isEqualTo(result.operationId());
        assertThatThrownBy(()->cancellation.cancel(cancel(id,"other",CancelSubscriptionUseCase.Mode.IMMEDIATE))).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->cancellation.cancel(cancel(id,"unknown",CancelSubscriptionUseCase.Mode.PERIOD_END))).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->withdrawal.withdraw(withdraw(id,"withdraw"))).isInstanceOf(PaymentApplicationException.class);
        assertThat(count("fake_subscription_operations")).isEqualTo(2);assertThat(count("payments")).isEqualTo(1);
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.SUCCESS);advance(INITIAL_TIME);
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(cancellation.cancel(cancel(id,"unknown",CancelSubscriptionUseCase.Mode.IMMEDIATE)).operationId()).isEqualTo(result.operationId());
    }
    @Test void pendingScheduledChangeRequiresExplicitProviderConfirmedClearBeforeEitherMode(){
        var id=paid().subscriptionId();allowImmediate();var s=sub(id);UUID target=UUID.randomUUID();
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',1000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',?,?,?)",target,site.value(),s.productId().value(),"p-"+target,Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        policies.saveTransition(new PlanTransition(site,s.productId(),s.billingPrice().priceId(),new PriceId(target),PlanTransition.Direction.DOWNGRADE,true,policies.load(site).version()));
        var q=bean(PreviewSubscriptionChangeUseCase.class).preview(new PreviewSubscriptionChangeUseCase.Query(context(null),id,new PriceId(target),ChangeTiming.NEXT_PERIOD));
        bean(ChangeSubscriptionPlanUseCase.class).change(new ChangeSubscriptionPlanUseCase.Command(context("change"),id,q.id()));drain();var before=sub(id);
        for(var mode:CancelSubscriptionUseCase.Mode.values())assertThatThrownBy(()->cancellation.cancel(cancel(id,"blocked-"+mode,mode))).isInstanceOf(PaymentApplicationException.class);
        assertThat(sub(id)).isEqualTo(before);bean(ClearSubscriptionChangeUseCase.class).clear(new ClearSubscriptionChangeUseCase.Command(context("clear"),id));drain();
        assertThat(sub(id).pendingChange()).isNull();assertThat(cancellation.cancel(cancel(id,"after-clear",CancelSubscriptionUseCase.Mode.IMMEDIATE))).isNotNull();drain();assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
    }
    @Test void cancellationRetainsOverdueBillAndNeverRefundsOrChargesFinalAmount(){
        var id=paid().subscriptionId();allowImmediate();controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.DECLINE);advance(sub(id).paidThrough());var debt=sub(id).debt();var payments=count("payments");
        controls.scenario(account.id(),id,FakeSubscriptionControlPort.Scenario.SUCCESS);assertThat(cancellation.cancel(cancel(id,"debt-finish",CancelSubscriptionUseCase.Mode.IMMEDIATE))).isNotNull();drain();
        assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);assertThat(sub(id).debt()).isEqualTo(debt);
        assertThat(count("payments")).isEqualTo(payments);assertThat(count("refunds")).isZero();assertThat(count("subscription_billing_records")).isEqualTo(2);assertThat(count("fake_subscription_invoices")).isEqualTo(2);
    }
    @Test void partialAndFullRefundLeaveSubscriptionActiveAndAccessIntact(){
        var id=paid().subscriptionId();var before=sub(id);var payment=new dev.genericpayments.platform.payments.domain.PaymentId(jdbc.queryForObject("select id from payments",UUID.class));
        var refunds=bean(dev.genericpayments.platform.payments.application.service.RefundCoordinator.class);var admission=bean(dev.genericpayments.platform.payments.application.service.ProviderInboxService.class);var paymentWorker=bean(dev.genericpayments.platform.payments.application.service.ProviderInboxWorker.class);var admin=UUID.randomUUID();
        for(long amount:new long[]{700,1300}){
            var refund=refunds.create(new dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand(site,payment,admin,amount,"EUR","REQUESTED_BY_CUSTOMER",null,"refund-"+amount,"task10"));
            admission.receive(new dev.genericpayments.platform.payments.application.model.ProviderEventEnvelope(account.provider(),account.id(),"refund-"+amount,refund.providerRefundReference(),dev.genericpayments.platform.payments.domain.PaymentEventType.REFUND_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of()));paymentWorker.runOnce();
            assertThat(sub(id)).isEqualTo(before);assertThat(new SubscriptionAccessPolicy().evaluate(sub(id),INITIAL_TIME).allowed()).isTrue();
        }
        assertThat(jdbc.queryForObject("select refunded_amount_minor from payments where id=?",Long.class,payment.value())).isEqualTo(2000);assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
    }
}
