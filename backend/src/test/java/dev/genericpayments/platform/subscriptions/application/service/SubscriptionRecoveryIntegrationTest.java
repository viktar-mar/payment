package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import org.junit.jupiter.api.Test;
class SubscriptionRecoveryIntegrationTest extends SubscriptionRecoveryFixture {
    @Test void unrelatedHistoricalPaidInvoiceCannotConfirmRecovery(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).paidThrough());
        controls.scenario(account.id(),r.subscriptionId(),Scenario.UNKNOWN);var result=recovery.recover(recoverCommand(r.subscriptionId(),"proof"));drain();
        var s=view(r.subscriptionId());var operation=op(result.operationId());
        apply(r.subscriptionId(),new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),s.cancellationAt(),s.invoices(),operation.providerOperationKey(),s.paymentMethodReady(),s.providerRevision()+1,s.observedAt(),new OperationObservation(operation.providerOperationKey(),OperationState.APPLIED)),"not-proof");
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);assertThat(sub(r.subscriptionId()).debt()).isNotNull();
    }
    @Test void retryActionIsOriginalKeyOnlyAndUpdatesSameCashObject(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).paidThrough());
        controls.scenario(account.id(),r.subscriptionId(),Scenario.REQUIRES_ACTION);var result=recovery.recover(recoverCommand(r.subscriptionId(),"new-attempt"));drain();
        var s=view(r.subscriptionId());assertThat(s.recoveryDisposition(s.invoices().getLast().reference())).isEqualTo(RecoveryDisposition.BLOCKED);
        assertThatThrownBy(()->recovery.recover(recoverCommand(r.subscriptionId(),"cannot-adopt"))).isInstanceOf(RuntimeException.class);
        assertThat(count("payments")).isEqualTo(3);assertThat(count("fake_subscription_cash_objects")).isEqualTo(3);
        restartedProvider().confirmAction(providerCommand(r.subscriptionId(),op(result.operationId()),s.invoices().getLast().reference()),result.nextAction().secret());drain();
        assertThat(count("payments")).isEqualTo(3);assertThat(count("fake_subscription_cash_objects")).isEqualTo(3);
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
    }
    @Test void missingOrDuplicateRecoveryObservationFailsClosed(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).paidThrough());
        var service=afterRetrieve(s->new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),s.cancellationAt(),s.invoices(),s.operationKey(),s.paymentMethodReady(),s.providerRevision(),s.observedAt(),s.operationObservation()));
        assertThatThrownBy(()->service.recover(recoverCommand(r.subscriptionId(),"missing"))).isInstanceOf(RuntimeException.class);
        var s=view(r.subscriptionId());var duplicated=new java.util.ArrayList<>(s.recoveryObservations());duplicated.add(s.recoveryObservations().getLast());
        assertThat(new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),s.cancellationAt(),s.invoices(),s.operationKey(),s.paymentMethodReady(),s.providerRevision(),s.observedAt(),s.operationObservation(),duplicated).recoveryDisposition(s.invoices().getLast().reference())).isEqualTo(RecoveryDisposition.BLOCKED);
        assertThat(count("subscription_operations")).isEqualTo(1);assertThat(count("fake_subscription_operations")).isEqualTo(1);
    }
    @Test void authorityIsRecheckedAfterProviderRead(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).paidThrough());var command=recoverCommand(r.subscriptionId(),"revoked-gap");
        var service=afterRetrieve(s->{jdbc.update("update customer_sessions set revoked_at=?,revoked_reason='ADMIN' where revoked_at is null",java.sql.Timestamp.from(s.observedAt()));return s;});
        assertThatThrownBy(()->service.recover(command)).isInstanceOf(RuntimeException.class);
        assertThat(count("subscription_operations")).isEqualTo(1);assertThat(count("fake_subscription_operations")).isEqualTo(1);
    }
    @Test void policyAndRevisionAreRecheckedAfterProviderRead(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).paidThrough());
        var changedPolicy=afterRetrieve(s->{var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,4,false,false,false,java.util.Set.of()),p.version());return s;});
        assertThatThrownBy(()->changedPolicy.recover(recoverCommand(r.subscriptionId(),"policy-gap"))).isInstanceOf(RuntimeException.class);
        var changedRevision=afterRetrieve(s->{jdbc.update("update subscriptions set revision=revision+1 where id=?",r.subscriptionId());return s;});
        assertThatThrownBy(()->changedRevision.recover(recoverCommand(r.subscriptionId(),"revision-gap"))).isInstanceOf(RuntimeException.class);
        assertThat(count("subscription_operations")).isEqualTo(1);assertThat(count("fake_subscription_operations")).isEqualTo(1);
    }
    @Test void wrongInvoiceAndOtherCustomerCannotReserveRecovery(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).paidThrough());
        var initial=jdbc.queryForObject("select id from subscription_billing_records where kind='INITIAL'",java.util.UUID.class);
        assertThatThrownBy(()->recovery.recover(new dev.genericpayments.platform.subscriptions.application.port.in.RecoverSubscriptionUseCase.Command(context("wrong-invoice"),r.subscriptionId(),initial))).isInstanceOf(RuntimeException.class);
        var other=customers.save(dev.genericpayments.platform.customers.domain.Customer.create(new dev.genericpayments.platform.customers.domain.CustomerId(java.util.UUID.randomUUID()),site,null,null,view(r.subscriptionId()).observedAt()));
        var wrong=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(actor,site,other.id(),"https://facts.test","other-customer","task8");
        assertThatThrownBy(()->recovery.recover(new dev.genericpayments.platform.subscriptions.application.port.in.RecoverSubscriptionUseCase.Command(wrong,r.subscriptionId(),sub(r.subscriptionId()).debt().billingRecordId()))).isInstanceOf(RuntimeException.class);
        assertThat(count("subscription_operations")).isEqualTo(1);
    }
    @Test void unknownRecoveryReplaysAndBlocksNewKeyUntilOriginalResolution(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.DECLINE);advance(sub(r.subscriptionId()).paidThrough());var debt=sub(r.subscriptionId()).debt();
        controls.scenario(account.id(),r.subscriptionId(),Scenario.UNKNOWN);var cmd=recoverCommand(r.subscriptionId(),"unknown");var result=recovery.recover(cmd);drain();
        assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);assertThat(sub(r.subscriptionId()).debt()).isEqualTo(debt);
        assertThat(recovery.recover(cmd).operationId()).isEqualTo(result.operationId());
        assertThatThrownBy(()->recovery.recover(recoverCommand(r.subscriptionId(),"other"))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_subscription_operations")).isEqualTo(2);assertThat(count("payments")).isEqualTo(2);
        controls.scenario(account.id(),r.subscriptionId(),Scenario.SUCCESS);advance(view(r.subscriptionId()).observedAt());
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(count("payments")).isEqualTo(3);
    }
    @Test void concurrentSameKeyAssociatesOnlyOnce() throws Exception {
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.REQUIRES_ACTION);advance(sub(r.subscriptionId()).paidThrough());var cmd=recoverCommand(r.subscriptionId(),"race");
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult> run=()->{start.await();return recovery.recover(cmd);};
            var a=pool.submit(run);var b=pool.submit(run);start.countDown();
            assertThat(a.get(10,java.util.concurrent.TimeUnit.SECONDS).operationId()).isEqualTo(b.get(10,java.util.concurrent.TimeUnit.SECONDS).operationId());
        }
        assertThat(count("subscription_operations")).isEqualTo(2);assertThat(count("fake_subscription_operations")).isEqualTo(2);
        assertThat(count("fake_subscription_cash_objects")).isEqualTo(2);assertThat(count("fake_subscription_invoices")).isEqualTo(2);
    }
    @Test void autonomousActionAssociatesExistingAttemptAndConfirmsExactDebt(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.REQUIRES_ACTION);advance(sub(r.subscriptionId()).paidThrough());
        var invoice=view(r.subscriptionId()).invoices().getLast();var cmd=recoverCommand(r.subscriptionId(),"associate");
        var result=recovery.recover(cmd);assertThat(result).isNotNull();assertThat(result.nextAction()).isNotNull();
        assertThat(view(r.subscriptionId()).invoices().getLast().cashObjects()).isEqualTo(invoice.cashObjects());
        assertThatThrownBy(()->recovery.recover(recoverCommand(r.subscriptionId(),"other"))).isInstanceOf(RuntimeException.class);
        var request=providerCommand(r.subscriptionId(),op(result.operationId()),invoice.reference());
        providers.forAccount(account).confirmAction(request,result.nextAction().secret());drain();
        assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(sub(r.subscriptionId()).debt()).isNull();
        assertThat(count("payments")).isEqualTo(2);assertThat(count("subscription_billing_records")).isEqualTo(2);
        assertThat(recovery.recover(cmd).operationId()).isEqualTo(result.operationId());
    }
    @Test void expiredAutonomousActionCanAssociateWithoutNewCashAndRejectsOldToken(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.REQUIRES_ACTION);advance(sub(r.subscriptionId()).paidThrough());
        var provider=providers.forAccount(account);var old=provider.nextAction(new Lookup(account,r.subscriptionId(),null,null));
        var originalCash=view(r.subscriptionId()).invoices().getLast().cashObjects();
        var origin=jdbc.queryForObject("select state->>'actionOperationKey' from fake_subscription_objects",String.class);
        advance(old.expiresAt());var cmd=recoverCommand(r.subscriptionId(),"expired-association");
        var result=recovery.recover(cmd);assertThat(result).isNotNull();assertThat(result.nextAction()).isNotNull();assertThat(result.nextAction().secret()).isNotEqualTo(old.secret());
        var request=providerCommand(r.subscriptionId(),op(result.operationId()),view(r.subscriptionId()).invoices().getLast().reference());
        assertThat(view(r.subscriptionId()).invoices().getLast().cashObjects()).isEqualTo(originalCash);
        assertThat(jdbc.queryForObject("select state->>'actionOperationKey' from fake_subscription_objects",String.class)).isEqualTo(origin);
        var fresh=restartedProvider();assertThat(fresh.nextAction(new Lookup(account,r.subscriptionId(),null,request.operation().providerOperationKey()))).isEqualTo(result.nextAction());
        assertThat(fresh.recover(request).action()).isEqualTo(result.nextAction());
        assertThatThrownBy(()->fresh.confirmAction(request,old.secret())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->recovery.recover(recoverCommand(r.subscriptionId(),"second-association"))).isInstanceOf(RuntimeException.class);
        fresh.confirmAction(request,result.nextAction().secret());drain();assertThat(count("payments")).isEqualTo(2);
    }
    @Test void autonomousUnknownWithNoCashCannotBeRetried(){
        var r=paid();controls.scenario(account.id(),r.subscriptionId(),Scenario.UNKNOWN);advance(sub(r.subscriptionId()).paidThrough());
        assertThat(view(r.subscriptionId()).invoices().getLast().cashObjects()).isEmpty();
        assertThatThrownBy(()->recovery.recover(recoverCommand(r.subscriptionId(),"unsafe"))).isInstanceOf(RuntimeException.class);
        assertThat(count("fake_subscription_operations")).isEqualTo(1);
    }
}
