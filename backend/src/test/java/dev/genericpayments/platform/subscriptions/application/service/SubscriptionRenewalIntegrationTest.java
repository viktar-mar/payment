package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.catalog.domain.*;
import org.junit.jupiter.api.Test;
class SubscriptionRenewalIntegrationTest extends SubscriptionRecoveryFixture {
    @Test void authoritativeUnpaidDeniesGraceAndCanFinishWithDebtRetained(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).paidThrough());
        var s=view(r.subscriptionId());var debt=sub(r.subscriptionId()).debt();
        apply(r.subscriptionId(),new Snapshot(s.subscriptionReference(),Subscription.Status.UNPAID,s.billingPrice(),s.period(),s.trial(),null,s.invoices(),s.operationKey(),s.paymentMethodReady(),s.providerRevision()+1,s.observedAt()),"unpaid");
        assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.UNPAID);
        assertThat(new SubscriptionAccessPolicy().evaluate(sub(r.subscriptionId()),s.observedAt()).allowed()).isFalse();
        apply(r.subscriptionId(),new Snapshot(s.subscriptionReference(),Subscription.Status.CANCELLED,s.billingPrice(),s.period(),s.trial(),null,s.invoices(),s.operationKey(),s.paymentMethodReady(),s.providerRevision()+2,s.observedAt()),"finished");
        assertThat(sub(r.subscriptionId()).nonTerminal()).isFalse();assertThat(sub(r.subscriptionId()).debt()).isEqualTo(debt);
        assertThatThrownBy(()->recovery.recover(recoverCommand(r.subscriptionId(),"terminal"))).isInstanceOf(RuntimeException.class);
    }
    @Test void scheduledTargetFailureChangesBillingButNotEntitlement(){
        var r=paid();var before=sub(r.subscriptionId());var old=before.billingPrice();var id=UUID.randomUUID();
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,?,price_version,price_type,4000,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at from prices where id=?",id,"target-"+id,price.value());
        var target=new SubscriptionPriceTerms(new PriceId(id),"target-"+id,1,old.productId(),new Money(4000,"EUR"),old.interval(),0,old.trialPolicy(),old.taxMode());
        var original=view(before.id());var end=before.paidThrough();TestClock.set(end);
        var period=new Subscription.Period(end,end.atZone(java.time.ZoneOffset.UTC).plusMonths(1).toInstant());
        var debt=new Invoice("scheduled-target-invoice",BillingRecord.Kind.RENEWAL,target,old,period,4000,"EUR",SettlementKind.OPEN,List.of());
        var invoices=new java.util.ArrayList<>(original.invoices());invoices.add(debt);
        apply(before.id(),new Snapshot(original.subscriptionReference(),Subscription.Status.PAST_DUE,target,period,null,null,invoices,null,true,original.providerRevision()+1,end),"scheduled-failed");
        assertThat(sub(before.id()).billingPrice()).isEqualTo(target);
        assertThat(sub(before.id()).entitledPrice()).isEqualTo(old);
        assertThat(sub(before.id()).paidThrough()).isEqualTo(end);
        assertThat(sub(before.id()).debt().graceDeadline()).isEqualTo(end.plusSeconds(3*86400));
    }
    @Test void annualRenewalCreatesSeparateInvoiceAndCash(){
        var id=UUID.randomUUID();jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,?,price_version,price_type,amount_minor,currency,'YEAR',trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at from prices where id=?",id,"annual-"+id,price.value());price=new PriceId(id);
        var r=paid();var first=sub(r.subscriptionId()).currentPeriod();advance(first.end());
        assertThat(sub(r.subscriptionId()).currentPeriod().start()).isEqualTo(first.end());
        assertThat(sub(r.subscriptionId()).paidThrough()).isEqualTo(first.end().atZone(java.time.ZoneOffset.UTC).plusYears(1).toInstant());
        assertThat(count("subscription_billing_records")).isEqualTo(2);assertThat(count("payments")).isEqualTo(2);
    }
    @Test void zeroGraceAndLateDuplicateFailureNeverMoveCapturedDeadline(){
        var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,0,false,false,false,Set.of()),p.version());
        var r=paid();var end=sub(r.subscriptionId()).paidThrough();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(end.plusSeconds(86400));
        var snapshot=view(r.subscriptionId());var debt=sub(r.subscriptionId()).debt();assertThat(debt.graceDeadline()).isEqualTo(end);
        assertThat(new SubscriptionAccessPolicy().evaluate(sub(r.subscriptionId()),end).allowed()).isFalse();
        p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,9,false,false,false,Set.of()),p.version());
        apply(r.subscriptionId(),snapshot,"late-duplicate");assertThat(sub(r.subscriptionId()).debt()).isEqualTo(debt);
    }
    @Test void recoveryOfExpiredInvoiceRecordsCashWithoutCurrentAccess(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).paidThrough());
        var debt=sub(r.subscriptionId()).debt();advance(debt.unpaidPeriod().end().plusSeconds(1));
        controls.scenario(account.id(),r.subscriptionId(),Scenario.SUCCESS);var result=recovery.recover(recoverCommand(r.subscriptionId(),"old-debt"));drain();
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(sub(r.subscriptionId()).paidThrough()).isEqualTo(debt.unpaidPeriod().end());
        assertThat(new SubscriptionAccessPolicy().evaluate(sub(r.subscriptionId()),debt.unpaidPeriod().end().plusSeconds(1)).allowed()).isFalse();
        assertThat(count("subscription_billing_records")).isEqualTo(2);assertThat(count("payments")).isEqualTo(3);
    }
    @Test void repeatFailureKeepsCapturedGraceWhileRecoveryCanRunAfterExpiry(){
        var r=paid();var end=sub(r.subscriptionId()).paidThrough();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(end);
        var debt=sub(r.subscriptionId()).debt();assertThat(debt.graceDeadline()).isEqualTo(end.plusSeconds(3*86400));
        var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,9,false,false,false,Set.of()),p.version());
        var result=recovery.recover(recoverCommand(r.subscriptionId(),"retry-fail"));assertThat(result).isNotNull();drain();
        assertThat(sub(r.subscriptionId()).debt()).isEqualTo(debt);TestClock.set(debt.graceDeadline());freshActor();
        assertThat(new SubscriptionAccessPolicy().evaluate(sub(r.subscriptionId()),debt.graceDeadline()).allowed()).isFalse();
        controls.scenario(account.id(),r.subscriptionId(),Scenario.SUCCESS);var paid=recovery.recover(recoverCommand(r.subscriptionId(),"retry-paid"));assertThat(paid).isNotNull();drain();
        assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);assertThat(op(paid.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("subscription_billing_records")).isEqualTo(2);assertThat(count("payments")).isEqualTo(4);
    }
}
