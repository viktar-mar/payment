package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import io.cucumber.java.en.*;

public class SubscriptionSteps {
    private final SubscriptionAcceptanceWorld w;
    public SubscriptionSteps(SubscriptionAcceptanceWorld world) {w=world;}
    @Given("an authenticated customer of a subscription-enabled site") public void initialize(){w.initialize();}
    @Given("a monthly subscription price with a {int} day optional-method trial") public void optional(int days){w.selectPrice(w.price("trial","MONTH",days,"OPTIONAL_DURING_TRIAL",2000));}
    @Given("a monthly subscription price with a {int} day required-method trial") public void required(int days){w.selectPrice(w.price("trial","MONTH",days,"REQUIRED_BEFORE_TRIAL",2000));}
    @When("the customer creates the subscription with recurring consent") public void create(){w.create();}
    @When("provider facts are processed") public void drain(){w.drainInbox();}
    @Then("access is allowed because of trial") public void trial(){assertThat(w.access().reason()).isEqualTo(AccessDecision.Reason.TRIAL);assertThat(w.access().allowed()).isTrue();w.rememberTrial();}
    @Then("no cash payment exists for the trial") public void noCash(){assertThat(w.paymentCount()).isZero();}
    @When("time reaches trial end without a payment method") public void trialEnd(){w.advance(w.subscription().trial().end());}
    @Then("the subscription is paused and access is denied") public void paused(){remainsPaused();denied();}
    @Then("the subscription remains paused") public void remainsPaused(){assertThat(w.subscription().billingStatus()).isEqualTo(Subscription.Status.PAUSED);}
    @Then("no renewal debt exists") public void noDebt(){assertThat(w.subscription().debt()).isNull();assertThat(w.count("subscription_billing_records","true")).isZero();}
    @When("a method setup is confirmed") public void setup(){w.setupMethod();}
    @When("the customer explicitly resumes and the first invoice is paid") public void resume(){w.resume();}
    @Then("access is allowed for the new paid period without another trial") public void resumed(){paid();assertThat(w.subscription().trial()).isEqualTo(w.originalTrial());assertThat(w.subscription().currentPeriod().start()).isEqualTo(w.originalTrial().end());assertThat(w.paymentCount()).isEqualTo(1);}
    @Then("access is denied pending initial confirmation") public void initial(){denied();assertThat(w.subscription().trial()).isNull();}
    @When("the original subscription action is confirmed") public void confirm(){w.confirmOriginalAction();}
    @When("the customer buys a paid {string} subscription") public void buy(String interval){w.createPaid("paid",interval);}
    @Then("access is allowed because of paid cash") public void paid(){assertThat(w.access().reason()).isEqualTo(AccessDecision.Reason.PAID);assertThat(w.access().allowed()).isTrue();}
    @When("the provider reaches the next billing period") public void renew(){w.advance(w.subscription().currentPeriod().end());}
    @Then("exactly {long} subscription cash payments exist") public void payments(long count){assertThat(w.paymentCount()).isEqualTo(count);}
    @Then("repeated provider delivery does not duplicate cash or lifecycle facts") public void duplicates(){var cash=w.paymentCount();var events=w.count("subscription_events","true");var snapshot=w.view();w.repeatFacts(snapshot);w.repeatFacts(snapshot);w.drainInbox();assertThat(w.paymentCount()).isEqualTo(cash);assertThat(w.count("subscription_events","true")).isEqualTo(events);}
    @When("the next renewal fails") public void fail(){w.failRenewal();}
    @Then("access is allowed during fixed grace") public void grace(){assertThat(w.access().reason()).isEqualTo(AccessDecision.Reason.GRACE);assertThat(w.access().validUntil()).isBeforeOrEqualTo(w.now().plusSeconds(300));assertThat(w.graceDeadline()).isEqualTo(w.originalPeriod().end().plusSeconds(3*86400));}
    @When("the site extends its future grace policy") public void extend(){w.extendGrace();}
    @Then("the current bill keeps its original grace deadline") public void fixed(){assertThat(w.subscription().debt().graceDeadline()).isEqualTo(w.graceDeadline());}
    @When("time reaches the fixed grace deadline") public void graceEnd(){w.advance(w.graceDeadline());}
    @Then("subscription access is denied") public void denied(){assertThat(w.access().allowed()).isFalse();assertThat(w.access().validUntil()).isNull();}
    @When("the customer recovers the same unpaid invoice twice") public void recover(){w.recover();}
    @Then("recovery created one successful cash charge") public void recovered(){assertThat(w.count("payments","status='SUCCEEDED'")).isEqualTo(2);assertThat(w.subscription().debt()).isNull();}
    @Then("another subscription for that product is rejected") public void unique(){assertThatThrownBy(w::create).isInstanceOf(RuntimeException.class);assertThat(w.count("subscriptions","true")).isEqualTo(1);}
    @Then("a different product can be subscribed independently") public void other(){var saved=w.result();w.createPaid("other","MONTH");assertThat(w.count("subscriptions","true")).isEqualTo(2);w.selectResult(saved);}
    @When("the first subscription is immediately cancelled") public void cancel(){w.cancelNow();}
    @Then("its product slot is released") public void released(){w.createPaid("paid","MONTH");paid();}
    @When("a same-product upgrade is confirmed with {string}") public void upgrade(String scenario){w.target(4000,PlanTransition.Direction.UPGRADE);w.bean(FakeSubscriptionControlPort.class).scenario(w.account().id(),w.result().subscriptionId(),FakeSubscriptionControlPort.Scenario.valueOf(scenario));w.change(w.quote(ChangeTiming.IMMEDIATE));}
    @Then("the upgrade grants {string} with {long} cash payments") public void upgraded(String entitled,long count){assertThat(w.subscription().entitledPrice().priceId()).isEqualTo(entitled.equals("target")?w.targetPrice():w.originalPrice().priceId());payments(count);}
    @When("a downgrade is scheduled for the next period") public void schedule(){w.target(1000,PlanTransition.Direction.DOWNGRADE);w.change(w.quote(ChangeTiming.NEXT_PERIOD));}
    @Then("the target is billed while the original price remains entitled") public void unpaidTarget(){assertThat(w.subscription().billingPrice().priceId()).isEqualTo(w.targetPrice());assertThat(w.subscription().entitledPrice()).isEqualTo(w.originalPrice());}
    @When("an upgrade quote expires before confirmation") public void expired(){w.target(4000,PlanTransition.Direction.UPGRADE);var q=w.quote(ChangeTiming.IMMEDIATE);w.advance(q.expiresAt());assertThatThrownBy(()->w.change(q)).isInstanceOf(RuntimeException.class);}
    @Then("the original price and single cash payment remain") public void unchanged(){assertThat(w.subscription().entitledPrice()).isEqualTo(w.originalPrice());payments(1);}
    @When("cancellation is scheduled and then withdrawn") public void withdraw(){w.bean(CancelSubscriptionUseCase.class).cancel(new CancelSubscriptionUseCase.Command(w.context(w.key()),w.result().subscriptionId(),CancelSubscriptionUseCase.Mode.PERIOD_END,w.result().subscriptionId()));w.drainInbox();assertThat(w.subscription().cancellation()).isNotNull();w.bean(WithdrawSubscriptionCancellationUseCase.class).withdraw(new WithdrawSubscriptionCancellationUseCase.Command(w.context(w.key()),w.result().subscriptionId()));w.drainInbox();}
    @Then("the paid period and cash count are unchanged") public void period(){assertThat(w.subscription().currentPeriod()).isEqualTo(w.originalPeriod());payments(1);}
    @When("a management Portal is opened") public void portal(){w.portal();}
    @Then("the renewal debt remains unpaid") public void debt(){assertThat(w.subscription().debt()).isNotNull();assertThat(w.count("payments","status='SUCCEEDED'")).isEqualTo(1);}
    @When("a previously captured paid provider fact arrives late") public void late(){w.repeatFacts(w.captured());w.drainInbox();}
    @When("initial creation returns {string}") public void initialOutcome(String outcome){w.initialOutcome(outcome);}
    @Then("the initial operation is {string} with {long} cash payments and access {word}") public void outcome(String status,long payments,String allowed){assertThat(w.operation().status().name()).isEqualTo(status);payments(payments);assertThat(w.access().allowed()).isEqualTo(Boolean.parseBoolean(allowed));assertThat(w.count("payments","status='SUCCEEDED'")).isEqualTo(Boolean.parseBoolean(allowed)?1:0);}
    @When("a fresh worker process observes provider success for the original operation") public void restarted() throws Exception {w.restartUnknown();}
    @Then("the renewed period remains authoritative") public void renewed(){assertThat(w.subscription().currentPeriod().start()).isEqualTo(w.originalPeriod().end());paid();}
    @When("the administrator fully refunds its confirmed subscription payment") public void refund(){w.refund();}
    @Then("a foreign Site cannot read or mutate that subscription") public void foreign(){w.foreignAuthority();}
    @Then("an old session without subscription scopes remains unauthorized") public void oldScopes(){w.oldScopes();}
    @Then("concurrent anonymization cannot erase the live subscription owner") public void anonymizationRace() throws Exception {w.anonymizationRace();}
    @Then("anonymization succeeds without removing historical cash") public void anonymized(){w.anonymizeTerminal();}
    @Then("migration validation preserves its cash and lifecycle identities") public void migrations(){w.validateMigration();}
    @Given("subscription callbacks are enabled") public void callbacks(){w.callbacks();}
    @Then("signed version three and four callbacks contain only canonical fields") public void signed() throws Exception {w.signedCallbacks();}
}
