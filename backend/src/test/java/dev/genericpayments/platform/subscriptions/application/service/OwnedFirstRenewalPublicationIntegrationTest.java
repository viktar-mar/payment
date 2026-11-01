package dev.genericpayments.platform.subscriptions.application.service;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalAdvanceAdapter;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.provider.application.service.OwnedFirstRenewalAdmissionService;
import dev.genericpayments.platform.provider.application.service.OwnedFirstRenewalPublicationService;
import dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionLifecycleLockPort;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.support.OwnedFirstRenewalBillingTime;
import dev.genericpayments.platform.support.OwnedSubscriptionBillingTime;
import dev.genericpayments.platform.support.StripeBillingClockConfirmationCompositionFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.*;
import static org.assertj.core.api.Assertions.*;

/** Hosted only: real initial confirmation, claim/READY/graph producers, FactService, cash and outbox. */
@Import(OwnedFirstRenewalPublicationIntegrationTest.RenewalTimeConfiguration.class)
class OwnedFirstRenewalPublicationIntegrationTest extends StripeBillingClockConfirmationCompositionFixture {
    @Autowired OwnedFirstRenewalStore renewals;
    @Autowired LockPaymentAccountPort accountLocks;
    @Autowired SubscriptionLifecycleLockPort lifecycleLocks;
    @Autowired OwnedFirstRenewalBillingTime renewalTime;

    @TestConfiguration(proxyBeanMethods=false)
    static class RenewalTimeConfiguration {
        @Bean static BeanFactoryPostProcessor selectRenewalTime() {
            return factory->factory.getBeanDefinition("ownedSubscriptionTime").setPrimary(false);
        }
        @Bean @Primary OwnedFirstRenewalBillingTime ownedFirstRenewalTime(JdbcTemplate jdbc,OwnedSubscriptionBillingTime original) {
            return new OwnedFirstRenewalBillingTime(jdbc,original);
        }
    }

    private void genuineInitialCash() {
        beginConfirmation();issueAndSelect();confirm("succeeded");
        var captured=fence();apply(paidObservation(),captured);assertPaid();
    }
    private Admission admitRenewal() {
        return new OwnedFirstRenewalAdmissionService(renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,subscriptions,Clock.systemUTC())
            .admit(receipt,"a".repeat(40));
    }
    private Admission paidAdmission() { genuineInitialCash();return admitRenewal(); }
    private StripeBillingFirstRenewalAdapter observer() {
        return new StripeBillingFirstRenewalAdapter(credentials,clients,renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,
            subscriptions,readPreparation,observations,bindings,Clock.systemUTC());
    }
    private OwnedFirstRenewalPublicationService publisher() {
        return new OwnedFirstRenewalPublicationService(renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,
            subscriptions,readPreparation,observations,facts,Clock.systemUTC());
    }
    private String clock(long frozen,String status) {
        var value=json(clockJson(owner));value.addProperty("frozen_time",frozen);value.addProperty("status",status);return value.toString();
    }
    private LaterReady advance(Admission admission,Step step) {
        long before=step==Step.BOUNDARY?FROZEN:receipt.periodEnd(),target=step==Step.BOUNDARY?receipt.periodEnd():receipt.periodEnd()+3600;
        http.reply(clock(before,"ready"));http.reply(clock(before,"advancing"));http.reply(clock(target,"ready"));
        return new StripeBillingFirstRenewalAdvanceAdapter(credentials,clients,accounts,operations,renewals,Clock.systemUTC()).advance(admission,step);
    }
    private void graph(LaterReady ready,boolean paid) {
        long b=receipt.periodEnd(),n=b+31*86400;
        var renewal=graph.invoice.deepCopy();renewal.addProperty("id","in_renewal");renewal.addProperty("billing_reason","subscription_cycle");
        renewal.addProperty("status",paid?"paid":"draft");renewal.addProperty("amount_paid",paid?1000:0);renewal.addProperty("amount_remaining",paid?0:1000);
        renewal.addProperty("amount_paid_off_stripe",0);
        var line=graph.line.deepCopy();line.addProperty("id","il_renewal");line.addProperty("invoice","in_renewal");
        line.addProperty("subscription","sub_owned");line.addProperty("subscription_item","si_owned");
        line.getAsJsonObject("period").addProperty("start",b);line.getAsJsonObject("period").addProperty("end",n);
        graph.item().addProperty("current_period_start",b);graph.item().addProperty("current_period_end",n);
        http.reply(clock(ready.observation().frozenTime(),"ready"));http.reply(customerJson("clock_owned"));http.reply(graph.remote.toString());
        http.reply(page(graph.invoice,renewal));http.reply(page(graph.line));http.reply(page(graph.allocation));http.reply(graph.pi.toString());
        http.reply(graph.charge.toString());http.reply(page(graph.allocation));http.reply(page(line));
        if(paid) {
            var allocation=graph.allocation.deepCopy();allocation.addProperty("id","inpay_renewal");allocation.addProperty("invoice","in_renewal");
            allocation.getAsJsonObject("payment").addProperty("payment_intent","pi_renewal");allocation.getAsJsonObject("status_transitions").addProperty("paid_at",b+11);
            var pi=graph.pi.deepCopy();pi.addProperty("id","pi_renewal");pi.addProperty("latest_charge","ch_renewal");
            var charge=graph.charge.deepCopy();charge.addProperty("id","ch_renewal");charge.addProperty("payment_intent","pi_renewal");
            http.reply(page(allocation));http.reply(pi.toString());http.reply(charge.toString());http.reply(page(allocation));
        } else http.reply(page());
        http.reply(clock(ready.observation().frozenTime(),"ready"));
    }
    private Pending boundary(Admission admission,LaterReady ready) {
        graph(ready,false);return (Pending)observer().observe(admission,ready);
    }
    private Paid paid(Admission admission) {
        var first=advance(admission,Step.BOUNDARY);var pending=boundary(admission,first);
        var second=advance(admission,Step.FINALIZATION);graph(second,true);
        var paid=(Paid)observer().observe(admission,second);
        assertThat(paid.boundary()).isEqualTo(pending.boundary());assertThat(paid.claim().ready()).isEqualTo(second);
        assertThat(underLifecycle(admission,()->renewals.readClaim(admission,second).orElseThrow())).isEqualTo(paid.claim());
        assertThat(underLifecycle(admission,()->renewals.boundaryInTransaction(admission))).isEqualTo(pending.boundary());
        assertThat(paid.fence()).isEqualTo(paid.claim().fence());assertThat(paid.graphAcceptedAt()).isBefore(paid.claim().deadlineAt());
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();assertThat(http.replies).isEmpty();return paid;
    }
    private <T>T underLifecycle(Admission admission,Supplier<T> work) {
        return transaction.required(()->{
            var ids=new TreeSet<UUID>();ids.add(admission.receipt().intent().createOperation());
            lifecycleOperations.unresolved(site,current().id()).forEach(operation->ids.add(operation.id()));for(var id:ids)lifecycleOperations.lock(id);
            lifecycleLocks.lockActive(site,customer);accountLocks.findByIdForUpdate(billing.id()).orElseThrow();subscriptions.lock(site,current().id());return work.get();
        });
    }
    private SubscriptionBillingTimePort.Sample sample() {
        return transaction.required(()->{
            var holder=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            var xact=jdbc.queryForObject("select pg_current_xact_id()::text",String.class);
            var result=renewalTime.sample(billing.id(),site,customer,Instant.now());
            assertThat(renewalTime.sampledConnectionHolder).isSameAs(holder);assertThat(renewalTime.sampledTransaction).isEqualTo(xact);return result;
        });
    }

    @Test void publishesOnlyFreshPaidOnce() {
        var admission=paidAdmission();var before=financialState();var paid=paid(admission);
        assertThat(financialState()).isEqualTo(before);
        var initialBill=jdbc.queryForObject("select row_to_json(b)::text from subscription_billing_records b where id=?",String.class,admission.bill());
        var initialPayment=jdbc.queryForObject("select row_to_json(p)::text from payments p where id=?",String.class,admission.payment());
        var originalCreate=currentOperation();var published=new AtomicReference<Publication>();
        assertThatCode(()->published.set(publisher().publish(admission,paid.ready(),paid)))
            .as("Genuine original confirmation and private READY2 CASH publish the first renewal atomically")
            .doesNotThrowAnyException();

        var expected=new Subscription.Period(Instant.ofEpochSecond(receipt.periodEnd()),Instant.ofEpochSecond(receipt.periodEnd()+31*86400));
        assertThat(current().currentPeriod()).isEqualTo(expected);assertThat(current().paidThrough()).isEqualTo(expected.end());
        assertThat(currentOperation()).isEqualTo(originalCreate);assertThat(currentOperation().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(jdbc.queryForObject("select row_to_json(b)::text from subscription_billing_records b where id=?",String.class,admission.bill())).isEqualTo(initialBill);
        assertThat(jdbc.queryForObject("select row_to_json(p)::text from payments p where id=?",String.class,admission.payment())).isEqualTo(initialPayment);
        assertThat(count("subscription_billing_records")).isEqualTo(2);assertThat(count("payments")).isEqualTo(2);
        assertThat(jdbc.queryForList("select settlement from subscription_billing_records",String.class)).containsOnly("CONFIRMED_CASH");
        var bill=jdbc.queryForObject("select id from subscription_billing_records where kind='RENEWAL' and provider_invoice_reference='in_renewal'",UUID.class);
        var payment=jdbc.queryForMap("""
            select id,status,source,subscription_billing_kind,subscription_id,billing_record_id,subscription_invoice_reference,
             subscription_period_start,subscription_period_end,provider_payment_reference,amount_minor,currency
            from payments where subscription_billing_kind='RENEWAL'
            """);
        assertThat(payment).containsEntry("status","SUCCEEDED").containsEntry("source","SUBSCRIPTION")
            .containsEntry("subscription_id",current().id()).containsEntry("billing_record_id",bill)
            .containsEntry("subscription_invoice_reference","in_renewal").containsEntry("provider_payment_reference","pi_renewal")
            .containsEntry("amount_minor",1000L).containsEntry("currency","EUR")
            .containsEntry("subscription_period_start",java.sql.Timestamp.from(expected.start()))
            .containsEntry("subscription_period_end",java.sql.Timestamp.from(expected.end()));
        assertThat(published.get().bill()).isEqualTo(bill);assertThat(published.get().payment()).isEqualTo(payment.get("id"));
        assertThat(published.get().projectedRevision()).isEqualTo(current().revision());
        assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).containsExactly("PERIOD_PAID","PERIOD_PAID");
        assertThat(count("payment_events")).isEqualTo(2);assertThat(count("outbox_events")).isEqualTo(4);
        assertThat(jdbc.queryForList("select callback_type from outbox_events",String.class))
            .containsExactlyInAnyOrder("payment.succeeded","subscription.updated","payment.succeeded","subscription.updated");
        assertThat(count("stripe_billing_first_renewal_publications")).isEqualTo(1);
        var committed=financialState();int calls=http.calls.size();
        assertThat(publisher().replayPublished(admission)).isEqualTo(published.get());
        assertThat(financialState()).isEqualTo(committed);assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        assertThatThrownBy(()->publisher().publish(admission,paid.ready(),paid)).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(committed);
    }

    @Test void neverFallsBackAfterRenewalAdmission() {
        genuineInitialCash();assertThat(sample().billingAt()).isEqualTo(Instant.ofEpochSecond(FROZEN));var admission=admitRenewal();
        assertThatThrownBy(this::sample).as("An admitted renewal without READY cannot reuse original frozen time").isInstanceOf(RuntimeException.class);
        var first=advance(admission,Step.BOUNDARY);
        assertThat(sample().billingAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));
        var pending=boundary(admission,first);renewals.prepare(admission,Step.FINALIZATION,pending.boundary(),Instant.now());
        assertThatThrownBy(this::sample).as("Any prepared step2 immediately supersedes READY1").isInstanceOf(RuntimeException.class);
        advance(admission,Step.FINALIZATION);
        assertThat(sample().billingAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+3600));
        assertThatThrownBy(()->queries.list(new QuerySubscriptionsUseCase.Query(scope.context().actor(),site,customer,null,null,10,null))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->queries.offers(scope.context().actor(),site,customer,current().productId())).isInstanceOf(RuntimeException.class);
        renewals.stop(admission,"PAYMENT_NOT_OBSERVED",Instant.now());
        assertThatThrownBy(this::sample).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->renewalTime.requireUnscopedAllowed(site,customer)).isInstanceOf(RuntimeException.class);
    }

    @Test void laterTimeDoesNotPay() {
        var admission=paidAdmission();var before=financialState();var first=advance(admission,Step.BOUNDARY);
        var boundaryAccess=queries.get(scope.context().actor(),site,current().id()).access();
        assertThat(boundaryAccess.evaluatedAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));
        assertThat(boundaryAccess.allowed()).as("Reaching E with only original cash must deny access").isFalse();
        boundary(admission,first);assertThat(financialState()).isEqualTo(before);
        var second=advance(admission,Step.FINALIZATION);
        var laterAccess=queries.get(scope.context().actor(),site,current().id()).access();
        assertThat(laterAccess.evaluatedAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+3600));assertThat(laterAccess.allowed()).isFalse();
        graph(second,true);assertThat(observer().observe(admission,second)).isInstanceOf(Paid.class);
        assertThat(current().paidThrough()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));
        assertThat(current().debt()).isNull();assertThat(financialState()).isEqualTo(before);
    }
}
