package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
class SubscriptionMethodIntegrationTest extends SubscriptionRecoveryFixture {
    @Autowired SetupSubscriptionMethodUseCase methods;
    @Autowired ConfirmSubscriptionActionUseCase confirmations;
    @Test void standaloneSetupRequiresExplicitConsent(){
        var id=paid().subscriptionId();
        assertThatThrownBy(()->methods.setup(new SetupSubscriptionMethodUseCase.Command(context("no-consent"),id,false))).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void standaloneSetupIssuesAnActionWithoutChangingPaidEntitlement(){
        var id=paid().subscriptionId();var before=sub(id);
        var r=methods.setup(new SetupSubscriptionMethodUseCase.Command(context("setup"),id,true));
        assertThat(r).isNotNull();assertThat(r.nextAction()).isNotNull();assertThat(sub(id)).isEqualTo(before);
    }
    @Test void methodSetupLeavesDebtGraceAndOtherProductUntouched(){
        var a=paid().subscriptionId();price=price(7);var b=start().subscriptionId();drain();var other=view(b);
        controls.scenario(account.id(),a,dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.DECLINE);
        advance(sub(a).currentPeriod().end());var before=sub(a);var invoices=view(a).invoices();var payments=count("payments");var paymentEvents=count("payment_events");
        controls.scenario(account.id(),a,dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.SUCCESS);
        var setup=methods.setup(new SetupSubscriptionMethodUseCase.Command(context("debt-setup"),a,true));
        confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("debt-confirm"),a,setup.operationId(),setup.nextAction().secret()));drain();
        assertThat(sub(a).billingStatus()).isEqualTo(before.billingStatus());assertThat(sub(a).debt()).isEqualTo(before.debt());
        assertThat(view(a).invoices()).isEqualTo(invoices);assertThat(count("payments")).isEqualTo(payments);
        assertThat(count("payment_events")).isEqualTo(paymentEvents);
        assertThat(view(b).paymentMethodReady()).isEqualTo(other.paymentMethodReady());assertThat(sub(b).trial()).isEqualTo(other.trial());
    }
    @Test void requiredMethodSetupContinuesFailedOriginalCreateAndConsumesTrialOnce(){
        price=price(7);var candidate=commands.reserveCreate(command("failed-create"));
        controls.scenario(account.id(),candidate.operation().subscriptionId(),dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.DECLINE);
        // Dispatch the already reserved original with the exact durable choice.
        var o=candidate.operation();var s=sub(o.subscriptionId());
        var reply=providers.forAccount(account).create(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(account,o,s.billingPrice(),null,null,null,null,true,true));
        commands.recordReply(o.id(),reply);drain();
        controls.scenario(account.id(),o.subscriptionId(),dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.SUCCESS);
        var setup=methods.setup(new SetupSubscriptionMethodUseCase.Command(context("continue"),o.subscriptionId(),true));
        assertThat(setup.operationId()).isEqualTo(o.id());assertThat(setup.nextAction()).isNotNull();
        confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("continue-confirm"),o.subscriptionId(),o.id(),setup.nextAction().secret()));drain();
        assertThat(sub(o.subscriptionId()).trial()).isNotNull();assertThat(count("subscription_operations")).isEqualTo(1);assertThat(count("payments")).isZero();
    }
    @Test void standaloneSetupDoesNotResumePausedOrRestartTrial(){
        price=price(7);var id=start().subscriptionId();drain();var trial=sub(id).trial();advance(trial.end());
        assertThat(sub(id).billingStatus()).isEqualTo(dev.genericpayments.platform.subscriptions.domain.Subscription.Status.PAUSED);
        var setup=methods.setup(new SetupSubscriptionMethodUseCase.Command(context("paused-setup"),id,true));
        confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("paused-confirm"),id,setup.operationId(),setup.nextAction().secret()));drain();
        assertThat(sub(id).billingStatus()).isEqualTo(dev.genericpayments.platform.subscriptions.domain.Subscription.Status.PAUSED);
        assertThat(sub(id).trial()).isEqualTo(trial);assertThat(count("payments")).isZero();assertThat(view(id).paymentMethodReady()).isTrue();
    }
    @Test void unknownSetupResolvesOriginalProviderOperationWithoutNewWork(){
        var id=paid().subscriptionId();controls.scenario(account.id(),id,dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.UNKNOWN);
        var command=new SetupSubscriptionMethodUseCase.Command(context("unknown-setup"),id,true);var setup=methods.setup(command);
        assertThat(setup.status()).isEqualTo(dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.UNKNOWN);
        assertThat(methods.setup(command).operationId()).isEqualTo(setup.operationId());
        assertThatThrownBy(()->methods.setup(new SetupSubscriptionMethodUseCase.Command(context("new-setup"),id,true))).isInstanceOf(PaymentApplicationException.class);
        controls.scenario(account.id(),id,dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.SUCCESS);advance(INITIAL_TIME.plusSeconds(1));
        var action=bean(GetSubscriptionActionUseCase.class).get(new GetSubscriptionActionUseCase.Query(context(null),id,setup.operationId()));
        confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("resolved-setup"),id,setup.operationId(),action.secret()));drain();
        assertThat(count("payments")).isEqualTo(1);assertThat(op(setup.operationId()).status()).isEqualTo(dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.SUCCEEDED);
    }
    @Test void returningPaidChoiceConfirmationAndSubsequentSetupNeverRepeatTrial(){
        price=price(7);var first=paid();
        // Fixture terminal projection releases the product slot, retaining consumed eligibility.
        tx.required(()->{var old=subscriptions.lock(site,first.subscriptionId());subscriptions.save(old.cancel("CONFIRMED",INITIAL_TIME),old.revision());return null;});
        var reserved=commands.reserveCreate(command("returning"));var o=reserved.operation();var s=reserved.subscription();
        assertThat(CreateSubscriptionFingerprint.startsTrial(o.fingerprint())).isFalse();
        controls.scenario(account.id(),s.id(),dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.REQUIRES_ACTION);
        var request=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(account,o,s.billingPrice(),null,null,null,null,true,false);
        var reply=providers.forAccount(account).create(request);commands.recordReply(o.id(),reply);
        var continued=methods.setup(new SetupSubscriptionMethodUseCase.Command(context("returning-setup"),s.id(),true));assertThat(continued.operationId()).isEqualTo(o.id());
        confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("returning-confirm"),s.id(),o.id(),continued.nextAction().secret()));drain();
        assertThat(sub(s.id()).trial()).isNull();assertThat(sub(s.id()).billingPrice().trialDays()).isEqualTo(7);assertThat(count("payments")).isEqualTo(1);
        controls.scenario(account.id(),s.id(),dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.SUCCESS);
        var setup=methods.setup(new SetupSubscriptionMethodUseCase.Command(context("later-setup"),s.id(),true));
        confirmations.confirm(new ConfirmSubscriptionActionUseCase.Command(context("later-confirm"),s.id(),setup.operationId(),setup.nextAction().secret()));drain();
        assertThat(sub(s.id()).trial()).isNull();assertThat(count("payments")).isEqualTo(1);
    }
    @Test void disabledPinnedAccountDuringSetupPreventsCapabilityExposure(){
        var id=paid().subscriptionId();var actual=restartedProvider();
        var proxy=(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort)java.lang.reflect.Proxy.newProxyInstance(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort.class.getClassLoader(),new Class<?>[]{dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort.class},(p,m,args)->{
            assertThat(tx.active()).isFalse();Object result;try{result=m.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            if(m.getName().equals("setup"))assertThat(jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",account.id().value())).isEqualTo(1);return result;
        });
        var service=new SubscriptionMethodService(bean(SubscriptionInteractionTransaction.class),a->proxy,bean(GetSubscriptionActionUseCase.class));
        assertThatThrownBy(()->service.setup(new SetupSubscriptionMethodUseCase.Command(context("disable-gap"),id,true))).isInstanceOf(PaymentApplicationException.class);
        assertThat(jdbc.queryForObject("select account_status from payment_accounts where id=?",String.class,account.id().value())).isEqualTo("DISABLED");
        assertThat(count("subscription_action_capabilities")).isZero();
    }
}
