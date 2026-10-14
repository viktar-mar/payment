package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubscriptionReconciliationIntegrationTest extends SubscriptionRecoveryFixture {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.class,names={"UNKNOWN","PROCESSING"})
    void unresolvedSetupEndsAtConfirmedCancellationAndStaysTerminalAfterJvmRestart(
            dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario scenario) throws Exception {
        var id=paid().subscriptionId();
        var boundary=sub(id).paidThrough();
        bean(CancelSubscriptionUseCase.class).cancel(new CancelSubscriptionUseCase.Command(context("boundary-cancel"),id,CancelSubscriptionUseCase.Mode.PERIOD_END,null));
        drain();
        controls.scenario(account.id(),id,scenario);
        var result=bean(SetupSubscriptionMethodUseCase.class).setup(new SetupSubscriptionMethodUseCase.Command(context("boundary-setup"),id,true));
        var original=op(result.operationId());
        var request=providerCommand(id,original,null);
        var provider=providers.forAccount(account);
        var receipt=provider.setup(request);
        String persistedReceipt=jdbc.queryForObject("select reply::text from fake_subscription_operations where operation_key=?",String.class,original.providerOperationKey());
        int invoices=count("fake_subscription_invoices"),cash=count("payments"),commands=count("fake_subscription_operations");
        assertThat(subscriptions.hasBlockingCustomerSubscriptions(site,customer.id())).isTrue();

        advance(boundary);
        assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(op(original.id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
        assertThat(provider.retrieve(new SubscriptionProviderModels.Lookup(account,id,sub(id).providerReference(),original.providerOperationKey())).operationObservation().state())
            .isEqualTo(SubscriptionProviderModels.OperationState.DECLINED);
        assertThat(subscriptions.hasBlockingCustomerSubscriptions(site,customer.id())).isFalse();
        assertThat(subscriptions.hasBlockingSiteSubscriptions(site)).isFalse();
        assertThat(subscriptions.hasNonTerminalAccountSubscriptions(account.id())).isFalse();
        assertThat(subscriptions.hasNonTerminalProduct(site,customer.id(),sub(id).productId())).isFalse();
        advance(boundary);
        assertThat(SubscriptionRestartHarness.run(app,boundary.plusSeconds(1),"reconcile",null)).isNotEqualTo(ProcessHandle.current().pid());
        controls.scenario(account.id(),id,dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.SUCCESS);
        advance(boundary.plusSeconds(2));
        assertThat(sub(id).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(op(original.id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
        assertThat(restartedProvider().setup(request)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("select reply::text from fake_subscription_operations where operation_key=?",String.class,original.providerOperationKey())).isEqualTo(persistedReceipt);
        assertThat(count("fake_subscription_invoices")).isEqualTo(invoices);
        assertThat(count("payments")).isEqualTo(cash);
        assertThat(count("fake_subscription_operations")).isEqualTo(commands);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"CREATE,true","CREATE,false","SETUP,true","SETUP,false","CHANGE,true","CHANGE,false","CANCEL,true","CANCEL,false","RECOVER,true","RECOVER,false"})
    void unknownOriginalOperationResolvesAcrossNewJvmWithoutReceiptMutation(SubscriptionOperation.Kind kind,boolean success)throws Exception {
        var unknown=dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.UNKNOWN;
        SubscriptionOperationResult result;
        if(kind==SubscriptionOperation.Kind.CREATE) {
            var reserved=commands.reserveCreate(command("unknown-create"));var o=reserved.operation();var s=reserved.subscription();
            controls.scenario(account.id(),s.id(),unknown);
            result=commands.recordReply(o.id(),providers.forAccount(account).create(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(account,o,s.billingPrice(),null,null,null,null,true,CreateSubscriptionFingerprint.startsTrial(o.fingerprint()))));
        } else {
            var id=paid().subscriptionId();
            if(kind==SubscriptionOperation.Kind.RECOVER){controls.scenario(account.id(),id,dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.DECLINE);advance(sub(id).paidThrough());}
            controls.scenario(account.id(),id,unknown);
            result=switch(kind) {
                case SETUP->bean(SetupSubscriptionMethodUseCase.class).setup(new SetupSubscriptionMethodUseCase.Command(context("unknown-setup"),id,true));
                case CANCEL->bean(CancelSubscriptionUseCase.class).cancel(new CancelSubscriptionUseCase.Command(context("unknown-cancel"),id,CancelSubscriptionUseCase.Mode.PERIOD_END,null));
                case RECOVER->recovery.recover(recoverCommand(id,"unknown-recovery"));
                case CHANGE->{
                    UUID target=UUID.randomUUID();
                    jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',1000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',?,?,?)",target,site.value(),sub(id).productId().value(),"target",java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
                    var targetId=new dev.genericpayments.platform.catalog.domain.PriceId(target);
                    policies.saveTransition(new PlanTransition(site,sub(id).productId(),sub(id).billingPrice().priceId(),targetId,PlanTransition.Direction.DOWNGRADE,true,policies.load(site).version()));
                    var quote=bean(PreviewSubscriptionChangeUseCase.class).preview(new PreviewSubscriptionChangeUseCase.Query(context(null),id,targetId,ChangeTiming.NEXT_PERIOD));
                    yield bean(ChangeSubscriptionPlanUseCase.class).change(new ChangeSubscriptionPlanUseCase.Command(context("unknown-change"),id,quote.id()));
                }
                default->throw new AssertionError(kind);
            };
        }
        var original=op(result.operationId());assertThat(original.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        String receipt=jdbc.queryForObject("select reply::text from fake_subscription_operations where operation_key=?",String.class,original.providerOperationKey());
        int financial=count("fake_subscription_operations"),local=count("subscription_operations"),invoices=count("fake_subscription_invoices");
        var at=bean(java.time.Clock.class).instant().plusSeconds(1);
        assertThat(SubscriptionRestartHarness.run(app,at,success?"resolve-success":"resolve-decline",original.id())).isNotEqualTo(ProcessHandle.current().pid());
        var resolved=op(original.id());assertThat(resolved.status()).as("%s positive %s observation after process restart",kind,success).isEqualTo(success?SubscriptionOperation.Status.SUCCEEDED:SubscriptionOperation.Status.FAILED);
        assertThat(resolved.providerOperationKey()).isEqualTo(original.providerOperationKey());assertThat(count("subscription_operations")).isEqualTo(local);assertThat(count("fake_subscription_operations")).isEqualTo(financial);
        assertThat(jdbc.queryForObject("select reply::text from fake_subscription_operations where operation_key=?",String.class,original.providerOperationKey())).isEqualTo(receipt);
        var proof=providers.forAccount(account).retrieve(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup(account,result.subscriptionId(),sub(result.subscriptionId()).providerReference(),original.providerOperationKey())).operationObservation();
        assertThat(proof.state()).isEqualTo(success?dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.OperationState.APPLIED:dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.OperationState.DECLINED);
        if(Set.of(SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Kind.CANCEL).contains(kind))assertThat(count("fake_subscription_invoices")).isEqualTo(invoices);
        int cash=count("payments"),cashEvents=count("payment_events");TestClock.set(at);drain();
        assertThat(count("payments")).isEqualTo(cash);assertThat(count("payment_events")).isEqualTo(cashEvents);
        TestClock.set(at.plusSeconds(30));bean(SubscriptionSweepWorker.class).runOnce();assertThat(count("payments")).isEqualTo(cash);assertThat(op(original.id()).status()).isEqualTo(resolved.status());
    }
    @Test void unknownOriginalCreateAndFutureCashRecoverAcrossActualJvmRestart() throws Exception {
        var reserved=commands.reserveCreate(command("process-original"));var id=reserved.subscription().id();
        long producer=SubscriptionRestartHarness.run(app,INITIAL_TIME,"produce-future",reserved.operation().id());
        assertThat(op(reserved.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);assertThat(sub(id).providerReference()).isNull();assertThat(op(reserved.operation().id()).providerReference()).isNull();assertThat(count("payments")).isZero();
        long before=SubscriptionRestartHarness.run(app,INITIAL_TIME,"reconcile",null);
        assertThat(before).isNotEqualTo(producer);assertThat(count("payments")).isEqualTo(1);assertThat(sub(id).paidThrough()).isNull();
        int events=count("payment_events");
        long boundary=SubscriptionRestartHarness.run(app,INITIAL_TIME.plusSeconds(60),"reconcile",null);
        assertThat(boundary).isNotIn(producer,before);assertThat(sub(id).paidThrough()).isEqualTo(INITIAL_TIME.plusSeconds(3600));
        assertThat(op(reserved.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(events);assertThat(count("fake_subscription_operations")).isEqualTo(1);
        TestClock.set(INITIAL_TIME.plusSeconds(60));drain();assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(events);
    }
    @Test void retainedFailedCreateOwnerStaysDistinctFromLaterSetupDuringReadAndApply() {
        price=price(3);var reserved=commands.reserveCreate(command("retained-owner"));var id=reserved.subscription().id();
        var provider=providers.forAccount(account);var o=reserved.operation();
        provider.create(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(account,o,reserved.subscription().billingPrice(),null,null,null,null,true,true));
        var observed=provider.retrieve(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup(account,id,null,o.providerOperationKey()));
        commands.recordReply(o.id(),new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Reply(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Outcome.DECLINED,observed.subscriptionReference(),null));
        var setup=new SubscriptionOperation(UUID.randomUUID(),site,customer.id(),id,SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.RESERVED,"internal","setup-retained","setup","setup-retained",sub(id).revision(),policies.load(site).version(),observed.subscriptionReference(),null,INITIAL_TIME,INITIAL_TIME,0);
        tx.required(()->operations.claim(setup));var request=providerCommand(id,setup,null);var reply=provider.setup(request);provider.confirmAction(request,reply.action().secret());
        assertThat(bean(SubscriptionReadPreparation.class).financial(sub(id)).id()).isEqualTo(setup.id());
        bean(ReconcileSubscriptionUseCase.class).reconcile(new ReconcileSubscriptionUseCase.Command(context("retained-reconcile"),id));
        assertThat(sub(id).trial()).isNotNull();assertThat(trials.find(site,customer.id(),sub(id).productId()).orElseThrow().reservedByOperation()).isEqualTo(o.id());
        assertThat(op(o.id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);assertThat(op(setup.id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(count("payments")).isZero();
    }
    @Test void inboxUsesOriginalFinancialKeyWithEitherNavigationUuidOrdering() {
        for (String prefix : List.of("00000000-0000-0000-0000-", "ffffffff-ffff-ffff-ffff-")) {
            factFixture();
            var id=paid().subscriptionId();
            var cancellation=bean(CancelSubscriptionUseCase.class).cancel(new CancelSubscriptionUseCase.Command(context("cancel"),id,CancelSubscriptionUseCase.Mode.PERIOD_END,null));
            int n=1;
            for(var kind:List.of(SubscriptionOperation.Kind.RECONCILE,SubscriptionOperation.Kind.PORTAL)) {
                var o=new SubscriptionOperation(UUID.fromString(prefix+String.format("%012d",n++)),site,customer.id(),id,kind,SubscriptionOperation.Status.RESERVED,"internal","navigation-"+kind,"fixture","unbacked-"+kind,sub(id).revision(),policies.load(site).version(),sub(id).providerReference(),null,INITIAL_TIME,INITIAL_TIME,0);
                tx.required(()->operations.claim(o));
            }
            relay.runOnce();worker.runOnce();
            assertThat(op(cancellation.operationId()).status()).as("Financial key survives navigation ordering %s",prefix).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
            assertThat(sub(id).cancellation()).isNotNull();
            assertThat(jdbc.queryForObject("select count(*) from provider_inbox where status<>'PROCESSED'",Integer.class)).isZero();
            assertThat(count("fake_subscription_operations")).isEqualTo(2);
        }
    }
    @Test void manualReconcileNeedsAuthorityAndIdempotencyAndDoesNotDuplicateFacts() {
        var result=start();var reconcile=bean(ReconcileSubscriptionUseCase.class);
        assertThatThrownBy(()->reconcile.reconcile(new ReconcileSubscriptionUseCase.Command(context(null),result.subscriptionId()))).isInstanceOf(RuntimeException.class);
        var command=new ReconcileSubscriptionUseCase.Command(context("manual"),result.subscriptionId());
        var first=reconcile.reconcile(command);assertThat(first.status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        var revision=sub(result.subscriptionId()).revision();var events=count("subscription_events");
        assertThat(reconcile.reconcile(command).operationId()).isEqualTo(first.operationId());
        reconcile.reconcile(new ReconcileSubscriptionUseCase.Command(context("fresh-read"),result.subscriptionId()));drain();
        assertThat(count("subscription_events")).isEqualTo(events);assertThat(sub(result.subscriptionId()).revision()).isEqualTo(revision);assertThat(count("payments")).isEqualTo(1);assertThat(count("fake_subscription_operations")).isEqualTo(1);
        sessions.logout(((SubscriptionActor.Customer)actor).session());assertThatThrownBy(()->reconcile.reconcile(command)).isInstanceOf(RuntimeException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"fingerprint","provider_operation_key"})
    void malformedRetainedCreateOwnerRejectsBeforeProviderRetrieve(String field) {
        price=price(3);var result=start();
        jdbc.update("update subscription_operations set "+field+"='invalid' where id=?",result.operationId());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry observed=a->{var actual=providers.forAccount(a);return (dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort)java.lang.reflect.Proxy.newProxyInstance(actual.getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort.class},(p,m,args)->{if(m.getName().equals("retrieve"))calls.incrementAndGet();try{return m.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});};
        var original=org.springframework.test.util.ReflectionTestUtils.getField(worker,"providers");
        try {org.springframework.test.util.ReflectionTestUtils.setField(worker,"providers",observed);relay.runOnce();worker.runOnce();}
        finally {org.springframework.test.util.ReflectionTestUtils.setField(worker,"providers",original);}
        assertThat(calls.get()).as("Invalid durable trial ownership must fail before the external read").isZero();
        assertThat(count("payments")).isZero();assertThat(sub(result.subscriptionId()).trial()).isNull();
    }
}
