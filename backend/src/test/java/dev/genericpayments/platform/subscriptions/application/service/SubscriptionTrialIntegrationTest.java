package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario;
import dev.genericpayments.platform.subscriptions.domain.*;
import org.junit.jupiter.api.Test;
class SubscriptionTrialIntegrationTest extends SubscriptionRecoveryFixture {
    @Test void requiredTrialCannotStartFromContradictoryMissingMethodObservation(){
        price=price(3);var id=java.util.UUID.randomUUID();jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,?,price_version,price_type,amount_minor,currency,billing_interval,trial_days,'REQUIRED_BEFORE_TRIAL',tax_mode,status,published_at,created_at,updated_at from prices where id=?",id,"required-"+id,price.value());price=new dev.genericpayments.platform.catalog.domain.PriceId(id);
        var r=start();drain();var s=view(r.subscriptionId());var period=new Subscription.Period(s.observedAt(),s.observedAt().plusSeconds(3*86400));
        var inconsistent=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot(s.subscriptionReference(),Subscription.Status.TRIALING,s.billingPrice(),period,period,null,s.invoices(),s.operationKey(),false,s.providerRevision()+1,s.observedAt());
        assertThatThrownBy(()->apply(r.subscriptionId(),inconsistent,"missing-method")).isInstanceOf(IllegalArgumentException.class);
        assertThat(sub(r.subscriptionId()).trial()).isNull();assertThat(trials.find(site,customer.id(),sub(r.subscriptionId()).productId()).orElseThrow().consumedAt()).isNull();
    }
    @Test void requiredTrialWaitsForConsentAndConfirmedMethod(){
        price=price(3);var id=java.util.UUID.randomUUID();jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,?,price_version,price_type,amount_minor,currency,billing_interval,trial_days,'REQUIRED_BEFORE_TRIAL',tax_mode,status,published_at,created_at,updated_at from prices where id=?",id,"required-"+id,price.value());price=new dev.genericpayments.platform.catalog.domain.PriceId(id);
        assertThatThrownBy(()->create.create(new CreateSubscriptionUseCase.Command(context("no-consent"),price,false))).isInstanceOf(RuntimeException.class);
        var r=start();drain();assertThat(sub(r.subscriptionId()).trial()).isNull();
        assertThat(trials.find(site,customer.id(),sub(r.subscriptionId()).productId()).orElseThrow().consumedAt()).isNull();
        var o=op(r.operationId());var request=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(account,o,sub(r.subscriptionId()).billingPrice(),null,null,null,null,true,true);
        providers.forAccount(account).confirmAction(request,r.nextAction().secret());drain();
        assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.TRIALING);
        assertThat(trials.find(site,customer.id(),sub(r.subscriptionId()).productId()).orElseThrow().consumedAt()).isNotNull();
        assertThat(count("payments")).isZero();assertThat(count("subscription_billing_records")).isZero();
    }
    @Test void unknownResumeBlocksNewKeyAndReplaysOriginalUntilConfirmed(){
        price=price(3);var r=paid();advance(sub(r.subscriptionId()).trial().end());setupMethod(r.subscriptionId());
        assertThatThrownBy(()->resuming.resume(new ResumeSubscriptionUseCase.Command(context("no-consent"),r.subscriptionId(),false))).isInstanceOf(RuntimeException.class);
        controls.scenario(account.id(),r.subscriptionId(),Scenario.UNKNOWN);
        var cmd=new ResumeSubscriptionUseCase.Command(context("resume-unknown"),r.subscriptionId(),true);var result=resuming.resume(cmd);drain();
        assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.PAUSED);
        assertThat(resuming.resume(cmd).operationId()).isEqualTo(result.operationId());
        assertThatThrownBy(()->resuming.resume(new ResumeSubscriptionUseCase.Command(context("another"),r.subscriptionId(),true))).isInstanceOf(RuntimeException.class);
        assertThat(count("payments")).isZero();controls.scenario(account.id(),r.subscriptionId(),Scenario.SUCCESS);advance(view(r.subscriptionId()).observedAt());
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void optionalTrialPausesWithoutDebtAndMethodAloneDoesNotResume(){
        price=price(3);var r=paid();var trial=sub(r.subscriptionId()).trial();advance(trial.end());
        assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.PAUSED);
        assertThat(sub(r.subscriptionId()).debt()).isNull();assertThat(count("subscription_billing_records")).isZero();assertThat(count("payments")).isZero();
        assertThat(new SubscriptionAccessPolicy().evaluate(sub(r.subscriptionId()),trial.end()).allowed()).isFalse();
        setupMethod(r.subscriptionId());assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.PAUSED);
        advance(trial.end().plusSeconds(5*86400));
        var result=resuming.resume(new ResumeSubscriptionUseCase.Command(context("resume"),r.subscriptionId(),true));
        assertThat(result).isNotNull();drain();
        assertThat(sub(r.subscriptionId()).currentPeriod().start()).isEqualTo(trial.end().plusSeconds(5*86400));
        assertThat(sub(r.subscriptionId()).trial()).isEqualTo(trial);assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("subscription_billing_records")).isEqualTo(1);
    }
    @Test void firstPosttrialFailureHasDebtButNeverPaidGrace(){
        price=price(3);var r=paid();setupMethod(r.subscriptionId());controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).trial().end());
        assertThat(sub(r.subscriptionId()).debt()).isNotNull();assertThat(sub(r.subscriptionId()).debt().graceDeadline()).isNull();
        assertThat(sub(r.subscriptionId()).paidThrough()).isNull();assertThat(new SubscriptionAccessPolicy().evaluate(sub(r.subscriptionId()),view(r.subscriptionId()).observedAt()).allowed()).isFalse();
    }
}
