package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario;
import dev.genericpayments.platform.subscriptions.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
class SubscriptionCancellationRaceIntegrationTest extends SubscriptionRecoveryFixture {
    @Autowired CancelSubscriptionUseCase cancellation;
    void allowImmediate(){var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,3,true,false,false,java.util.Set.of()),p.version());}
    CancelSubscriptionUseCase.Command immediate(java.util.UUID id,String key){return new CancelSubscriptionUseCase.Command(context(key),id,CancelSubscriptionUseCase.Mode.IMMEDIATE,id);}
    SubscriptionCancellationService decorated(java.util.function.UnaryOperator<Snapshot> hook,boolean safe){
        SubscriptionProviderRegistry registry=a->{var actual=restartedProvider();return (SubscriptionProviderPort)java.lang.reflect.Proxy.newProxyInstance(SubscriptionProviderPort.class.getClassLoader(),new Class<?>[]{SubscriptionProviderPort.class},(proxy,method,args)->{
            assertThat(tx.active()).as("Provider calls hold no platform transaction or row locks").isFalse();
            if(method.getName().equals("capabilities"))return new Capabilities(true,safe,true,true);
            Object value;try{value=method.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            return method.getName().equals("retrieve")?hook.apply((Snapshot)value):value;
        });};return new SubscriptionCancellationService(bean(SubscriptionCancellationTransaction.class),registry);
    }
    @Test void unsafeCapabilityAndRevokedAuthorityAndChangedPolicyOrRevisionCannotDispatch(){
        var id=paid().subscriptionId();allowImmediate();
        assertThatThrownBy(()->decorated(s->s,false).cancel(immediate(id,"unsafe"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->decorated(s->{var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,4,true,false,false,java.util.Set.of()),p.version());return s;},true).cancel(immediate(id,"policy-gap"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->decorated(s->{jdbc.update("update subscriptions set revision=revision+1 where id=?",id);return s;},true).cancel(immediate(id,"revision-gap"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->decorated(s->{sessions.logout(((SubscriptionActor.Customer)actor).session());return s;},true).cancel(immediate(id,"logout-gap"))).isInstanceOf(RuntimeException.class);
        assertThat(count("subscription_operations")).isEqualTo(1);assertThat(count("fake_subscription_operations")).isEqualTo(1);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void competingSetupChangeRecoveryAndCancelKeysCannotBypassMutationSerialization(){
        var id=paid().subscriptionId();allowImmediate();var s=sub(id);
        for(var kind:java.util.List.of(SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Kind.CANCEL)){
            var pending=new SubscriptionOperation(java.util.UUID.randomUUID(),site,customer.id(),id,kind,SubscriptionOperation.Status.RESERVED,"internal","key-"+kind,"fixture","fixture-"+kind,s.revision(),policies.load(site).version(),s.providerReference(),null,INITIAL_TIME,INITIAL_TIME,0);
            tx.required(()->operations.claim(pending));
            jdbc.update("update subscription_operations set status='UNKNOWN' where id=?",pending.id());
            for(var mode:CancelSubscriptionUseCase.Mode.values())assertThatThrownBy(()->cancellation.cancel(new CancelSubscriptionUseCase.Command(context("compete-"+kind+mode),id,mode,id))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->bean(WithdrawSubscriptionCancellationUseCase.class).withdraw(new WithdrawSubscriptionCancellationUseCase.Command(context("withdraw-"+kind),id))).isInstanceOf(RuntimeException.class);
            assertThat(sub(id)).isEqualTo(s);assertThat(count("fake_subscription_operations")).isEqualTo(1);
            jdbc.update("update subscription_operations set status='FAILED' where id=?",pending.id());
        }
    }
    @Test void reconcileAndPortalAreNotCompetingMutations(){
        var id=paid().subscriptionId();allowImmediate();var s=sub(id);
        var fixtureIds=new java.util.ArrayList<java.util.UUID>();
        for(var kind:java.util.List.of(SubscriptionOperation.Kind.RECONCILE,SubscriptionOperation.Kind.PORTAL)){
            var o=new SubscriptionOperation(java.util.UUID.randomUUID(),site,customer.id(),id,kind,SubscriptionOperation.Status.RESERVED,"internal","key-"+kind,"fixture","fixture-"+kind,s.revision(),policies.load(site).version(),s.providerReference(),null,INITIAL_TIME,INITIAL_TIME,0);
            tx.required(()->operations.claim(o));fixtureIds.add(o.id());
        }
        var result=new java.util.concurrent.atomic.AtomicReference<SubscriptionOperationResult>();
        assertThatCode(()->result.set(cancellation.cancel(immediate(id,"navigation-independent")))).doesNotThrowAnyException();
        for(var fixtureId:fixtureIds)assertThat(op(fixtureId).status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
        apply(id,retrieve(result.get()),"cancellation-with-unresolved-navigation");
        assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);assertThat(op(result.get().operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        // These guard-only fixture keys have no provider receipts. Generic inbox key
        // selection belongs to reconciliation coverage, not this dispatch assertion.
        for(var fixtureId:fixtureIds)jdbc.update("update subscription_operations set status='FAILED' where id=?",fixtureId);
        drain();
    }
    @Test void unknownCancellationAlsoBlocksRecoveryAndPlanPreview(){
        var id=paid().subscriptionId();allowImmediate();controls.scenario(account.id(),id,Scenario.DECLINE);advance(sub(id).paidThrough());
        controls.scenario(account.id(),id,Scenario.UNKNOWN);var result=cancellation.cancel(immediate(id,"unknown"));drain();
        assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        assertThatThrownBy(()->recovery.recover(recoverCommand(id,"recover-bypass"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->bean(PreviewSubscriptionChangeUseCase.class).preview(new PreviewSubscriptionChangeUseCase.Query(context(null),id,price,ChangeTiming.NEXT_PERIOD))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_subscription_operations")).isEqualTo(2);assertThat(sub(id).debt()).isNotNull();
    }
    @Test void concurrentSameKeyProducesOneDurableCancellationAndOneProviderMutation()throws Exception{
        var id=paid().subscriptionId();allowImmediate();var request=immediate(id,"same-key");
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return cancellation.cancel(request);});var b=pool.submit(()->{gate.await();return cancellation.cancel(request);});gate.countDown();
            assertThat(a.get(10,java.util.concurrent.TimeUnit.SECONDS).operationId()).isEqualTo(b.get(10,java.util.concurrent.TimeUnit.SECONDS).operationId());
        }
        drain();assertThat(count("subscription_operations")).isEqualTo(2);assertThat(count("fake_subscription_operations")).isEqualTo(2);assertThat(count("payments")).isEqualTo(1);assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
    }
    @Test void wrongBoundaryOrUnrelatedOperationMarkerCannotConfirmUnknownSchedule(){
        var id=paid().subscriptionId();controls.scenario(account.id(),id,Scenario.UNKNOWN);
        var result=cancellation.cancel(new CancelSubscriptionUseCase.Command(context("schedule-proof"),id,CancelSubscriptionUseCase.Mode.PERIOD_END,null));drain();var o=op(result.operationId());var s=view(id);
        var wrong=s.period().end().plusSeconds(86400);
        apply(id,new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),wrong,s.invoices(),o.providerOperationKey(),s.paymentMethodReady(),s.providerRevision()+1,s.observedAt(),new OperationObservation(o.providerOperationKey(),OperationState.APPLIED)),"wrong-boundary");
        assertThat(sub(id).cancellation()).isNull();assertThat(op(o.id()).status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        apply(id,new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),sub(id).paidThrough(),s.invoices(),"unrelated",s.paymentMethodReady(),s.providerRevision()+2,s.observedAt(),new OperationObservation(o.providerOperationKey(),OperationState.APPLIED)),"wrong-marker");
        assertThat(sub(id).cancellation()).isNull();assertThat(op(o.id()).status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
    }
    @Test void acceptedImmediateRequestPreservesAccessAndSlotUntilTerminalFact(){
        var r=paid();var id=r.subscriptionId();allowImmediate();var before=sub(id);
        var result=cancellation.cancel(new CancelSubscriptionUseCase.Command(context("cancel"),id,CancelSubscriptionUseCase.Mode.IMMEDIATE,id));
        assertThat(result).as("Cancellation returns its durable operation").isNotNull();
        assertThat(sub(id)).isEqualTo(before);assertThat(new SubscriptionAccessPolicy().evaluate(sub(id),INITIAL_TIME).allowed()).isTrue();
        assertThatThrownBy(this::start).isInstanceOf(RuntimeException.class);
        drain();assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(new SubscriptionAccessPolicy().evaluate(sub(id),INITIAL_TIME).allowed()).isFalse();
        assertThat(start().subscriptionId()).isNotEqualTo(id);assertThat(count("refunds")).isZero();
    }
    @Test void lateRenewalCashAfterExternalCancellationAndResubscribeNeverRevivesEitherProjection(){
        var old=paid();var id=old.subscriptionId();controls.scenario(account.id(),id,Scenario.UNKNOWN);advance(sub(id).paidThrough());
        // Independent provider cancellation races an autonomous renewal still settling.
        jdbc.update("update fake_subscription_objects set state=jsonb_set(jsonb_set(state,'{status}','\"CANCELLED\"'),'{revision}',to_jsonb((state->>'revision')::bigint+1)) where subscription_id=?",id);
        apply(id,view(id),"external-cancel");assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        var fresh=paid();var newBefore=sub(fresh.subscriptionId());var oldBefore=sub(id);var priorEvents=count("payment_events");
        controls.scenario(account.id(),id,Scenario.SUCCESS);advance(view(id).observedAt());
        assertThat(view(id).status()).as("Late real cash cannot reactivate provider lifecycle").isEqualTo(Subscription.Status.CANCELLED);
        assertThat(sub(id)).isEqualTo(oldBefore);assertThat(sub(fresh.subscriptionId())).isEqualTo(newBefore);
        assertThat(count("payments")).isEqualTo(3);assertThat(count("payment_events")).isEqualTo(priorEvents+1);
        apply(id,view(id),"late-duplicate");assertThat(count("payments")).isEqualTo(3);assertThat(count("payment_events")).isEqualTo(priorEvents+1);
        assertThat(jdbc.queryForObject("select count(*) from payments where subscription_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(count("refunds")).isZero();assertThat(count("fake_subscription_invoices")).isEqualTo(3);
    }
}
